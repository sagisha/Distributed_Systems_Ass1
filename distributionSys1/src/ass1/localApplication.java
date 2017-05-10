package ass1;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
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


public class localApplication {


    private static String getUserDataScript() {
        ArrayList<String> lines = new ArrayList<String>();
        lines.add("#!/bin/sh");
        lines.add("\n");
//        lines.add("output : { all : '| tee -a /var/log/user-data.log' }");
        lines.add("amzFile=\"manager.jar\"");
        lines.add("bucket=\"shaked\"");
        lines.add("resource=\"/${bucket}/${amzFile}\"");
        lines.add("contentType=\"application/java-archive\"");
        lines.add("dateValue=`date -R`");
        lines.add("stringToSign=\"GET\\n\\n${contentType}\\n${dateValue}\\n${resource}\"");
        lines.add("s3Key=\"XXXXXXXXXXXXXXXXXXXX"");
        lines.add("s3Secret=\XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
        lines.add("signature=`echo -en ${stringToSign} | openssl sha1 -hmac ${s3Secret} -binary | base64`");
        lines.add("curl  -H \"Host: ${bucket}.s3.amazonaws.com\" \\\n" +
                "     -H \"Date: ${dateValue}\" \\\n" +
                "     -H \"Content-Type: ${contentType}\" \\\n" +
                "     -H \"Authorization: AWS ${s3Key}:${signature}\" \\\n" +
                "     https://${bucket}.s3.amazonaws.com/${amzFile} -o $amzFile");
        lines.add("java -jar manager.jar");
        lines.add("\n");
        String str ="";
        for(int i=0;i<lines.size();i++)
            str= str.concat(lines.get(i)).concat("\n");
        return  new String(Base64.encodeBase64(str.getBytes()));
    }


    public static void main(String[] args) throws IOException {

        if (args.length == 3 || args.length == 4) {
            String inputFile = args[0];
            String outPutFileName = args[1];
            String n = args[2];
            List<String> managerID = new ArrayList<String>();
            String programID = String.valueOf(System.currentTimeMillis());
            AWSCredentials credentials = new PropertiesCredentials(localApplication.class.getResourceAsStream("/AWSCredentials.properties"));
            AmazonEC2 ec2;
            ec2 = AmazonEC2ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(credentials)).withRegion("us-east-1").build();

            // checks if the instance running, if not- activate it.
            try {
                boolean create=true;
                DescribeInstancesRequest request = new DescribeInstancesRequest();
                ArrayList<String> values= new ArrayList<String>();
                values.add("manager");
                Filter managerInstances= new Filter("tag:role", values);
                DescribeInstancesResult result = ec2.describeInstances(request.withFilters(managerInstances));
                List<Reservation> reservList =result.getReservations();
                for (Reservation reservation: reservList){
                    List<Instance> instancesList = reservation.getInstances();
                    for(Instance currInstance: instancesList) {
                        if (currInstance.getState().getName().equals("running") || currInstance.getState().getCode()==0 ) {
                            create = false;
                            break;
                        }
                    }
                }
                if(create){
                    System.out.println("Creating manager EC2 node\n");
                    RunInstancesRequest runInstancesRequest = new RunInstancesRequest().withKeyName("shaked").withInstanceType("t2.micro").withImageId("ami-c58c1dd3")
                            .withMaxCount(1).withMinCount(1).withTagSpecifications(new TagSpecification().withTags(new Tag("role", "manager")).withResourceType(ResourceType.Instance)).withUserData(getUserDataScript());
                    managerID.add(ec2.runInstances(runInstancesRequest).getReservation().getInstances().get(0).getInstanceId()); // stores the manager ID
                }
                else{System.out.println("MANAGER ALLREADY EXIST");}


                S3 s3 = new S3(credentials, "distributedsysfiles1");
                String fileAddress = s3.uploadFileToS3(inputFile);
                System.out.println("Sending message to SQS: the location of the input file on S3\n");
                SQS localAppsToManager = new SQS(credentials, "localAppsToManager"); //creates SQS for sending
                String msgToManager = programID.concat("\t").concat(n).concat("\t").concat(fileAddress);
                //System.out.print("================================"+ msgToManager);
                localAppsToManager.sendMessage(msgToManager);

                SQS managerToLocalApps = new SQS(credentials, "managerToLocalApps"); //creates SQS
                System.out.println("Waiting for message from managerToLocalApps queue...\n");
                List<Message> messages = managerToLocalApps.receiveMsg();
                boolean gotTheRightMsg = false;
                while (!gotTheRightMsg) {
                    for (Message message : messages) {
                        if (message.getBody().equals(programID)) {
                            S3Object summaryFile = s3.downloadFilefromS3(programID + ".txt");

                            //  HTML output
                            File htmlFile = new File(outPutFileName);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(summaryFile.getObjectContent()));
                            BufferedWriter writer = new BufferedWriter(new FileWriter(htmlFile));

                            writer.write("<!DOCTYPE html><html><head><style>td {padding-right: 50px;} h2{color:#cc3939;}</style></head><body><h2>Summary File</h2><table><thead><th>Action</th><th>Old Link</th><th>New S3 Link</th></thead>");
                            String line;
                            String[] parsedLine;
                            while ((line = reader.readLine()) != null) {
                                System.out.println(line);
                                parsedLine = line.split("\t");
                                writer.write("<tr><td>" + parsedLine[0] + "</td><td><a href=" + parsedLine[1] + ">" + parsedLine[1] + "</a></td><td><a href='" + parsedLine[2] + "'>" + parsedLine[2] + "</a></td></tr>");
                            }
                            writer.write("</table></body></html>");
                            writer.close();

                            gotTheRightMsg = true;
                            break;
                        }
                    }
                    messages = managerToLocalApps.receiveMsg();
                }

                gotTheRightMsg= false;
                if(args.length==4 && args[3].equals("terminate")){
                    localAppsToManager.sendMessage("terminate\t" + managerID );
                    List<Message> terminationMessage = managerToLocalApps.receiveMsg();
                    System.out.println("Waiting for temination");
                    while (!gotTheRightMsg) {
                        for (Message message : terminationMessage) {
                            if (message.getBody().equals(programID)) {
                                break;
                            }
                        }
                        terminationMessage = managerToLocalApps.receiveMsg();
                    }
                }
                System.out.println("Good Bye!!");


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
            }catch(Exception e){
                e.printStackTrace();
            }
        }
        else
            System.out.println("WRONG NUMBER OF ARGUMENTS");

    }


}
