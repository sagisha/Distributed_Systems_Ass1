import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.model.Message;
import org.apache.commons.codec.binary.Base64;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class managerMsgHandler extends Thread {

    private String localAppID;
    private int n;
    private String inputFileLocation;


    public managerMsgHandler(String localAppID, int n, String inputFileLocation){
        super();
        this.localAppID = localAppID;
        this.n = n;
        this.inputFileLocation = inputFileLocation;

    }

    @Override public void run () {

        try {
            int msgCount= 0;
            AWSCredentials credentials = new PropertiesCredentials(main.class.getResourceAsStream("/AWSCredentials.properties"));
            AmazonEC2 ec2;
            ec2 = AmazonEC2ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(credentials)).withRegion("us-east-1").build();

            //download the file
            S3 s3 = new S3(credentials);
            S3Object currFile = s3.downloadFilefromS3(inputFileLocation);

            //get access to the relevant SQS
            SQS managerToWorkers = new SQS("managerToWorkers");
            SQS workersToManager = new SQS("workersToManager");
            SQS managerToLocalApps = new SQS("managerToLocalApps");

            //reading currFile and send SQS messager per line.
            System.out.println("reading currFile and send SQS messager per line.\n");
            BufferedReader reader = new BufferedReader(new InputStreamReader(currFile.getObjectContent()));
            String line;
            while ((line = reader.readLine()) != null) {
                msgCount++;
                managerToWorkers.sendMessage(line + "\t" + localAppID);
            }

            int numOfWorkers = (msgCount + n - 1)/n ;
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            ArrayList<String> values= new ArrayList<String>();
            values.add("worker");
            Filter workersInstances = new Filter("tag:role", values);
            DescribeInstancesResult result = ec2.describeInstances(request.withFilters(workersInstances));
            int currNumOfWorkers = 0;
            List<Reservation> reservList =result.getReservations();
            for (Reservation reservation: reservList){
                List<Instance> instancesList = reservation.getInstances();
                for(Instance currInstance: instancesList) {
                    if (currInstance.getState().getName().equals("running") || currInstance.getState().getName().equals("pending"))
                        currNumOfWorkers ++;
                }
            }

            System.out.println("creating " + (numOfWorkers-currNumOfWorkers) + " workers");
            if(currNumOfWorkers == 0 || numOfWorkers > currNumOfWorkers){
                int workersToCreate=numOfWorkers-currNumOfWorkers;
                RunInstancesRequest runInstancesRequest = new RunInstancesRequest().withKeyName("shaked").withInstanceType("t2.micro").withImageId("ami-c58c1dd3")
                        .withMaxCount(workersToCreate).withMinCount(workersToCreate).withTagSpecifications(new TagSpecification().withTags(new Tag("role", "worker")).withResourceType(ResourceType.Instance)).withUserData(getUserDataScript());
                    ec2.runInstances(runInstancesRequest);
            }

            //create summary file
            System.out.println("creating summary file\n");
            System.out.println("Receiving message from workersToManager queue\n");
            try{
                PrintWriter writer = new PrintWriter(localAppID + ".txt", "UTF-8");
                while(msgCount > 0){
                    List<Message> returnMsgs = workersToManager.receiveMsg();
                    while(returnMsgs.size() == 0){
                        returnMsgs = workersToManager.receiveMsg();
                    }
                    for(Message returnMsg : returnMsgs){
                        localAppID = returnMsg.getBody().substring(0,13);
                        String responseMsg = returnMsg.getBody().substring(13);
                        writer.println(responseMsg);
                        workersToManager.deleteMessage(returnMsg);          //delete message from the queue
                        }
                    msgCount--;
                    }
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            s3.uploadFileToS3(localAppID + ".txt");               //uploads summary file to S3
            managerToLocalApps.sendMessage(localAppID);                     //sends message to local application

        }catch(AmazonServiceException ase){
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to Amazon S3, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        }catch(AmazonClientException ace){
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with S3, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }catch(Exception e) {
            e.printStackTrace();
        }
    }

    private static String getUserDataScript() {

        ArrayList<String> lines = new ArrayList<String>();
        lines.add("#!/bin/sh");
        lines.add("\n");
        lines.add("amzFile=\"worker.jar\"");
        lines.add("bucket=\"shaked\"");
        lines.add("resource=\"/${bucket}/${amzFile}\"");
        lines.add("contentType=\"application/java-archive\"");
        lines.add("dateValue=`date -R`");
        lines.add("stringToSign=\"GET\\n\\n${contentType}\\n${dateValue}\\n${resource}\"");
        lines.add("s3Key=\"XXXXXXXXXXXXXXXXXXXX"");
        lines.add("s3Secret=\"XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"");
        lines.add("signature=`echo -en ${stringToSign} | openssl sha1 -hmac ${s3Secret} -binary | base64`");
        lines.add("curl  -H \"Host: ${bucket}.s3.amazonaws.com\" \\\n" +
                "     -H \"Date: ${dateValue}\" \\\n" +
                "     -H \"Content-Type: ${contentType}\" \\\n" +
                "     -H \"Authorization: AWS ${s3Key}:${signature}\" \\\n" +
                "     https://${bucket}.s3.amazonaws.com/${amzFile} -o $amzFile");
        lines.add("java -jar worker.jar");
        String str ="";
        for(int i=0;i<lines.size();i++)
            str= str.concat(lines.get(i)).concat("\n");

        return  new String(Base64.encodeBase64(str.getBytes()));
    }
}
