package ass1Worker;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.sqs.model.Message;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.tools.imageio.*;
import static org.apache.commons.io.FileUtils.writeStringToFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.List;
import org.apache.commons.io.*;

public class Worker {

    private AWSCredentials credentials;
    private SQS workersQueue;
    private SQS managerQueue;
    private S3 s3;
    private List<Message> messagesFromQueue = null;
    private Message currentMessageProcessed;


    public Worker()throws Exception {
        this.credentials = new PropertiesCredentials(Worker.class.getResourceAsStream("/AWSCredentials.properties"));
        this.workersQueue = new SQS(credentials, "managerToWorkers");
        this.managerQueue = new SQS(credentials, "workersToManager");

    }

    public void work() throws Exception {
        System.out.println("******************************************");
        System.out.println("------- WORKER IS GOING TO WORK --------");
        this.messagesFromQueue = workersQueue.receiveMsg();
        while(messagesFromQueue.size()== 0)
            this.messagesFromQueue = workersQueue.receiveMsg();
        currentMessageProcessed = messagesFromQueue.get(0);
        parseMsg();
        workersQueue.deleteMessage(currentMessageProcessed);
    }

    public void parseMsg() {
        String[] parsedMsg = currentMessageProcessed.getBody().split("\t");
        String action, fileLink, localID;
        action = parsedMsg[0];
        fileLink = parsedMsg[1];
        localID = parsedMsg[2];
        System.out.println("- PARSE MSG \n-- ACTION: " + action + "\n-- FILELINK: " + fileLink + "\n-- LOCAL-ID: " + localID);
        System.out.println("**************************************\n");

        String fileName = fileLink.substring(fileLink.lastIndexOf("/") + 1);
        try{
            File myPdf= new File("myPdf.pdf");
            FileUtils.copyURLToFile(new URL(fileLink), myPdf);
            PDDocument document = PDDocument.load(myPdf);
            fileName = fileName.replace(".pdf", "");
            System.out.println(fileName+ " name + new path"+ myPdf.getPath());
            if (action.equals("ToHTML")){
                convertToHTML(document, fileName, parsedMsg);
                System.out.println(fileName+ " name + new path"+ myPdf.getPath());
            }
            else if (action.equals("ToImage")){
                convertToImage(document, fileName, parsedMsg);
                System.out.println(fileName+ " name + new path"+ myPdf.getPath());
            }
            else if (action.equals("ToText")){
                System.out.println(fileName+ " name + new path"+ myPdf.getPath());
                convertToText(document, fileName, parsedMsg);
            }
            else
                System.out.println("UNKNOWN ACTION");
        } catch (Exception e){
            System.out.println(e.getMessage());
            e.printStackTrace();
            try{
                managerQueue.sendMessage(localID.concat(action).concat("\t").concat(fileLink).concat("\t").concat(e.getMessage()));
                workersQueue.deleteMessage(currentMessageProcessed);
            }catch (Exception ex){ex.printStackTrace();}
        }
    }


    public void convertToImage(PDDocument pdfFile, String fileName, String[] msgDetails) throws Exception {
        System.out.println("**************************************");
        System.out.println("WORKER IS CONVERTING " + fileName + " TO IMAGE!");

        PDFRenderer pdfRenderer = new PDFRenderer(pdfFile);
        this.s3 = new S3(credentials,msgDetails[2]+"uploadedimages");
        System.out.println("**************************************\n");
        BufferedImage bim = pdfRenderer.renderImageWithDPI(0,300, ImageType.RGB);
        // suffix in filename will be used as the file format
        String newImgName =fileName.concat(".png");
        System.out.println("Creating Image Files");
        ImageIOUtil.writeImage(bim, newImgName, 300);
        //upload to S3
        System.out.println("Current Image Name : "+ newImgName);
        s3.uploadFileToS3(newImgName);
        //Sending msg to queue
        System.out.println("Sending Complete Message To Manager. \n");
        String S3URL="https://s3.amazonaws.com/";
        S3URL=S3URL.concat(msgDetails[2]).concat("uploadedimages/").concat(newImgName);
        String messageToManager=msgDetails[2].concat(msgDetails[0]).concat("\t").concat(msgDetails[1]).concat("\t").concat(S3URL);
        System.out.println(messageToManager);
        managerQueue.sendMessage(messageToManager);

        pdfFile.close();
    }


    public void convertToHTML(PDDocument pdfFile, String fileName, String[] msgDetails) throws Exception {
        System.out.println("WORKER IS CONVERTING " + fileName + " TO HTML!");

        PDDocument firstPage = new PDDocument();
        firstPage.addPage(pdfFile.getDocumentCatalog().getPages().get(0));
        firstPage.save("firstPage.pdf");
        firstPage.close();

        PDFTextStripper converter = new PDFTextStripper();
        String text = converter.getText(firstPage);
        File htmlFile = new File(fileName.concat(".html"));
        BufferedWriter writer = new BufferedWriter(new FileWriter(htmlFile));
        String[] lines = text.split("\\r?\\n");
        writer.write("<!DOCTYPE html><html><head><title>Converted Page</title></head><body><p>");
        for(int i=0;i<lines.length;i++){
            writer.write(lines[i]+ "</br>");
        }
        writer.write("</p></body></html>");
        writer.close();
        //upload to S3
        this.s3 = new S3(credentials,msgDetails[2]+"uploadedhtml");
        s3.uploadFileToS3(fileName.concat(".html"));

        //Sending msg to queue
        System.out.println("Sending Complete Message To Manager. \n");
        String S3URL="https://s3.amazonaws.com/";
        S3URL=S3URL.concat(msgDetails[2]).concat("uploadedhtml/").concat(fileName).concat(".html");
        String messageToManager=msgDetails[2].concat(msgDetails[0]).concat("\t").concat(msgDetails[1]).concat("\t").concat(S3URL);
        System.out.println(messageToManager);
        managerQueue.sendMessage(messageToManager);
        System.out.println("**************************************");

    }

    public void convertToText(PDDocument pdfFile, String fileName, String[] msgDetails ) throws Exception{
        System.out.println("WORKER IS CONVERTING " + fileName + " TO TEXT!");
        PDFTextStripper converter = new PDFTextStripper();
        String text = converter.getText(pdfFile);
        File textFile= new File(fileName.concat(".txt"));
        writeStringToFile(textFile, text, "UTF-8");
        pdfFile.close();

        //upload to S3
        this.s3 = new S3(credentials,msgDetails[2]+"uploadedtext");
        s3.uploadFileToS3(fileName.concat(".txt"));
        //Sending msg to queue
        System.out.println("Sending Complete Message To Manager.\n");
        String S3URL="https://s3.amazonaws.com/";
        S3URL=S3URL.concat(msgDetails[2]).concat("uploadedtext/").concat(fileName).concat(".txt");
        String messageToManager=msgDetails[2].concat(msgDetails[0]).concat("\t").concat(msgDetails[1]).concat("\t").concat(S3URL);
        managerQueue.sendMessage(messageToManager);
        System.out.println("**************************************\n");
    }

}
