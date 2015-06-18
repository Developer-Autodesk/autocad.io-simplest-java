package com.autodesk;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.net.*;

public class Main {

    //note that this workitem does not specify the "Resource" property for its "Result" output argument
    //this causes AutoCAD.IO to use its own storage. If you want to store the output on your own storage then
    //provide a valid URL for the "Resource" property.
    private static final String WORK_ITEM = "{\n" +
            "    \"@odata.type\": \"#ACES.Models.WorkItem\",\n" +
            "    \"ActivityId\": \"PlotToPDF\",\n" +
            "    \"Arguments\": {\n" +
            "        \"@odata.type\": \"#ACES.Models.Arguments\",\n" +
            "        \"InputArguments@odata.type\": \"#Collection(ACES.Models.Argument)\",\n" +
            "        \"InputArguments\": [\n" +
            "            {\n" +
            "                \"@odata.type\": \"#ACES.Models.Argument\",\n" +
            "                \"Name\": \"HostDwg\",\n" +
            "                \"Resource\": \"http://download.autodesk.com/us/samplefiles/acad/blocks_and_tables_-_imperial.dwg\"\n" +
            "            }\n" +
            "        ],\n" +
            "        \"OutputArguments@odata.type\": \"Collection(ACES.Models.Argument)\",\n" +
            "        \"OutputArguments\": [\n" +
            "            {\n" +
            "                \"@odata.type\": \"#ACES.Models.Argument\",\n" +
            "                \"HttpVerb@odata.type\": \"#ACES.Models.HttpVerbType\",\n" +
            "                \"HttpVerb\": \"POST\",\n" +
            "                \"Name\": \"Result\",\n" +
            "                \"StorageProvider@odata.type\": \"#ACES.Models.StorageProvider\",\n" +
            "                \"StorageProvider\": \"Generic\"\n" +
            "            }\n" +
            "        ]\n" +
            "    }\n" +
            "}";

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        final String token = getToken("your consumer key", "your consumer secret");
        System.out.print("Submitting workitem...");
        final String workItemId = submitWorkItem(token, WORK_ITEM);
        System.out.println("Ok.");
        //this is a console app so we must poll. In a server we could also receive a callback by
        //specifying the "Status" pseudo output argument with a Resource attribute pointing to our
        //callback url
        String status;
        do {
            System.out.println("Sleeping for 2s...");
            Thread.sleep(2000);
            System.out.print("Checking work item status=");
            status = pollWorkItem(token, workItemId);
            System.out.println(status);
        } while (status.compareTo("Pending")==0 || status.compareTo("InProgress")==0);
        if (status.compareTo("Succeeded")==0)
            downloadResults(token, workItemId);
    }

    //obtain authorization token
    static String getToken(final String consumerKey, final String consumerSecret) throws IOException, ParseException {
        final String url = "https://developer.api.autodesk.com/authentication/v1/authenticate";
        final HttpPost post =   new HttpPost(url);
        List<NameValuePair> form = new ArrayList<NameValuePair>();
        form.add(new BasicNameValuePair("client_id", consumerKey));
        form.add(new BasicNameValuePair("client_secret", consumerSecret));
        form.add(new BasicNameValuePair("grant_type", "client_credentials"));
        post.setEntity(new UrlEncodedFormEntity(form, "UTF-8"));

        final HttpClient client = HttpClientBuilder.create().build();
        final HttpResponse response = client.execute(post);

        if (response.getStatusLine().getStatusCode() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatusLine().getStatusCode());
        }

        final BufferedReader br = new BufferedReader(new InputStreamReader((response.getEntity().getContent())));
        final JSONParser jsonParser = new JSONParser();
        final JSONObject jsonObj = (JSONObject) jsonParser.parse(br);
        return (String)jsonObj.get("token_type") + " " + (String)jsonObj.get("access_token");
    }

    //submit the workitem described by 'content' parameter. Returns the id of the workitem
    static String submitWorkItem(final String token, final String content) throws IOException, ParseException {
        final String url = "https://developer.api.autodesk.com/autocad.io/us-east/v2/WorkItems";
        //final String url = "http://localhost:39284/api/us-east/v2/WorkItems";
        final HttpPost post =   new HttpPost(url);
        addODataHeaders(post, token);
        post.setEntity(new StringEntity(content));

        final HttpClient client = HttpClientBuilder.create().build();
        final HttpResponse response = client.execute(post);

        if (response.getStatusLine().getStatusCode() != 201) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatusLine().getStatusCode());
        }
        final BufferedReader br = new BufferedReader(new InputStreamReader((response.getEntity().getContent())));
        final JSONParser jsonParser = new JSONParser();
        final JSONObject jsonObj = (JSONObject) jsonParser.parse(br);
        return (String)jsonObj.get("Id");
    }

    //polls the workitem for its status. Returns the status.
    static String pollWorkItem(final String token, final String id) throws IOException, ParseException {
        final String url = String.format("https://developer.api.autodesk.com/autocad.io/us-east/v2/WorkItems('%s')/Status", id);
        final HttpGet get =   new HttpGet(url);
        addODataHeaders(get, token);

        final HttpClient client = HttpClientBuilder.create().build();
        final HttpResponse response = client.execute(get);

        if (response.getStatusLine().getStatusCode() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatusLine().getStatusCode());
        }

        final BufferedReader br = new BufferedReader(new InputStreamReader((response.getEntity().getContent())));
        final JSONParser jsonParser = new JSONParser();
        final JSONObject jsonObj = (JSONObject) jsonParser.parse(br);
        return (String)jsonObj.get("value");
    }

    //downloads the workitem results and status report.
    static void downloadResults(final String token, final String id) throws IOException, ParseException {
        final String url = String.format("https://developer.api.autodesk.com/autocad.io/us-east/v2/WorkItems('%s')", id);
        final HttpGet get =   new HttpGet(url);
        addODataHeaders(get, token);

        final HttpClient client = HttpClientBuilder.create().build();
        final HttpResponse response = client.execute(get);

        if (response.getStatusLine().getStatusCode() != 200) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + response.getStatusLine().getStatusCode());
        }

        final BufferedReader br = new BufferedReader(new InputStreamReader((response.getEntity().getContent())));
        final JSONParser jsonParser = new JSONParser();
        final JSONObject jsonObj = (JSONObject) jsonParser.parse(br);
        final String outputURL = (String)((JSONObject)((JSONArray)((JSONObject) jsonObj.get("Arguments")).get("OutputArguments")).get(0)).get("Resource");
        FileUtils.copyURLToFile(new URL(outputURL), new File("d:/result.pdf"));
        final String reportUrl = (String)((JSONObject) jsonObj.get("StatusDetails")).get("Report");
        FileUtils.copyURLToFile(new URL(reportUrl), new File("d:/report.txt"));

    }

    // add necessary OData related headers and Authorization header
    static void addODataHeaders(HttpRequestBase request, String token)
    {
        request.addHeader("OData-Version","4.0");
        request.addHeader("OData-MaxVersion", "4.0");
        request.addHeader("Content-Type", "application/json;odata.metadata=minimal");
        request.addHeader("Accept", "application/json;odata.metadata=minimal");
        request.addHeader("Accept-Charset","UTF-8");
        request.addHeader("Authorization", token);
    }
}
