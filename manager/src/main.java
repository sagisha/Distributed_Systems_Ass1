
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.sqs.model.Message;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class main  {

    public static HashMap<String,Message> allMessages= new HashMap();
    public static void main(String[] args) throws IOException {

        try {

            List<String> managerID = new ArrayList<String>();
            AWSCredentials credentials = new PropertiesCredentials(main.class.getResourceAsStream("/AWSCredentials.properties"));
            AmazonEC2 ec2;
            ec2 = AmazonEC2ClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(credentials)).withRegion("us-east-1").build();

            //define queues
            SQS localAppsToManager = new SQS("localAppsToManager");
            SQS managerToLocalApps = new SQS("managerToLocalApps");
            ExecutorService executor = Executors.newFixedThreadPool(30);

            //Attributes to get the approximate # of messages
//            List<String> attributes = new ArrayList<String>();
//            attributes.add("ApproximateNumberOfMessages");
//            attributes.add("ApproximateNumberOfMessagesDelayed");
//            attributes.add("ApproximateNumberOfMessagesNotVisible");
//            GetQueueAttributesResult result = localAppsToMenager.sqs.getQueueAttributes(localAppsToMenager.queueUrl, attributes);
//            int appNumOfMsg =Integer.parseInt(result.getAttributes().get("ApproximateNumberOfMessages")) +
//                    Integer.parseInt(result.getAttributes().get("ApproximateNumberOfMessagesDelayed")) +
//                    Integer.parseInt(result.getAttributes().get("ApproximateNumberOfMessagesNotVisible"));
            //System.out.println("# of messages in the queue: " + appNumOfMsg + "\n");


            // Main loop
            String localAppID = "";
            while(true) {
                List<Message> message = localAppsToManager.receiveMsg();
                for (Message currMessage : message) {
                    String[] stringSplit = currMessage.getBody().split("\t");
                    System.out.println("currMessage: " + currMessage.getBody().toString());
                    if (!(stringSplit[0].equals("terminate"))) {
                        //creates thread that responsible to handle the message and starts it.
                        //split the message content to variables.
                        localAppID = stringSplit[0];
                        if (!(allMessages.containsKey(localAppID))) {
                            allMessages.put(localAppID, currMessage);
                            int n = Integer.parseInt(stringSplit[1]);
                            String inputFileLocation = stringSplit[2];
                            Runnable msgHandler = new managerMsgHandler(localAppID, n, inputFileLocation);
                            executor.execute(msgHandler);
                        }
                    } else {
                        managerID.add(stringSplit[1]);

                        // ================= threads Termination=====================
                        System.out.println("Terminating threads...\n ");
                        executor.shutdown();
                        while (!executor.isTerminated()) {
                        }
                        System.out.println("All threads terminated\n");

                        // ================= workers Termination=====================
                        //get all workers IDs
                        List<String> workersIds = new ArrayList<String>();
                        DescribeInstancesRequest request = new DescribeInstancesRequest();
                        ArrayList<String> values= new ArrayList<String>();
                        values.add("worker");
                        Filter workersInstances = new Filter("tag:role", values);
                        DescribeInstancesResult result = ec2.describeInstances(request.withFilters(workersInstances));
                        List<Reservation> reservList =result.getReservations();
                        for (Reservation reservation: reservList){
                            List<Instance> instancesList = reservation.getInstances();
                            for(Instance currInstance: instancesList) {
                                if (currInstance.getState().getName().equals("running"))
                                    workersIds.add(currInstance.getInstanceId());
                            }
                        }

                        //terminate all the workers
                        TerminateInstancesRequest terminateWorkersRequest = new TerminateInstancesRequest();
                        terminateWorkersRequest.setInstanceIds(workersIds);
                        TerminateInstancesResult terminateWorkersResult = ec2.terminateInstances(terminateWorkersRequest);
                        int workerNum = 1;
                        for (InstanceStateChange item : terminateWorkersResult.getTerminatingInstances()) {
                            if (!(item.getInstanceId().equals(managerID))) {
                                System.out.println("Worker " + workerNum + " terminated");
                                workerNum++;
//                                while (!(item.getCurrentState().getName().toString().equals("terminated"))) {
//                                    Thread.sleep(5000);
//                                }
                            }
                        }

                        // ================= Manager Termination=====================
                        TerminateInstancesRequest terminateManagerRequest = new TerminateInstancesRequest();
                        terminateManagerRequest.setInstanceIds(managerID);
                        TerminateInstancesResult terminatManagereResult = ec2.terminateInstances(terminateManagerRequest);
                        for (InstanceStateChange item : terminatManagereResult.getTerminatingInstances()) {
                            if (item.getInstanceId().equals(managerID)) {
                                while (item.getCurrentState().getName().toString().equals("terminated")) {
                                    Thread.sleep(5000);
                                }
                                break;
                            }
                        }
                        System.out.println("manager terminated\n");
                        managerToLocalApps.sendMessage(localAppID);
                        return;
                    }
                }
            }
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

}
