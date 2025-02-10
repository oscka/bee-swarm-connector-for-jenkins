package io.jenkins.plugins.bee;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.netflix.graphql.dgs.client.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BeeRestCommunication {

    public String validateRestHost(String restHost){
        String tmp = restHost.substring(restHost.length()-1);
        String rest = "graphql";
        if(tmp.equalsIgnoreCase("/")){
            return restHost+rest;
        }else{
            return restHost+"/"+rest;
        }
    }

    public ArrayList<Object> requestScaleOut(String restHost, String projectName, String swarmName, String token) {
        ArrayList<Object> arrResp = new ArrayList<>();
        arrResp.add(validateRestHost(restHost));
        try {
            JsonParser parser = new JsonParser();
            RestTemplate dgsRestTemplate = new RestTemplate();
            DefaultGraphQLClient graphQLClient = new DefaultGraphQLClient(validateRestHost(restHost));
            Map<String, Object> swarmActionInput = new HashMap<>();
            Map<String, String> id = new HashMap<>();
            id.put("projectName", projectName);
            id.put("swarmName", swarmName);
            swarmActionInput.put("id", id);
            swarmActionInput.put("action", "SCALE_OUT");
            Map<String, Object> variable = new HashMap<>();
            variable.put("input", swarmActionInput);
            String query = "mutation Swarm ($input: SwarmActionInput!) {\n" +
                    "    actionSwarm(input: $input) {\n" +
                    "        status\n" +
                    "        result\n" +
                    "    }\n" +
                    "}";

            GraphQLResponse graphQLResponse = graphQLClient.executeQuery(query, variable, (url, headers, body) -> {
                /**
                 * The requestHeaders providers headers typically required to call a GraphQL endpoint, including the Accept and Content-Type headers.
                 * To use RestTemplate, the requestHeaders need to be transformed into Spring's HttpHeaders.
                 */
                HttpHeaders requestHeaders = new HttpHeaders();
                requestHeaders.add("Authorization", token);
                requestHeaders.add("Content-Type","application/json");
                requestHeaders.add("Connection","keep-alive");
                requestHeaders.add("x-api-key","32538e82-d0f2-4980-8412-59b0dbc0b341");
                UUID uuid = UUID.randomUUID();
                requestHeaders.add("msg-id",String.valueOf(uuid));
                requestHeaders.putAll(headers);

                /**
                 * Use RestTemplate to call the GraphQL service.
                 * The response type should simply be String, because the parsing will be done by the GraphQLClient.
                 */
                ResponseEntity<String> exchange = dgsRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity(body, requestHeaders), String.class);

                JsonObject jsonObject = (JsonObject) parser.parse(exchange.getBody());

                if(jsonObject.get("errors") != null) {
                    arrResp.add("[FAILED]");
                    arrResp.add(jsonObject.get("errors").toString());
                }

                /**
                 * Return a HttpResponse, which contains the HTTP status code and response body (as a String).
                 * The way to get these depend on the HTTP client.
                 */
                return new HttpResponse(exchange.getStatusCodeValue(), exchange.getBody());
            });
            Map<String, Object> response = graphQLResponse.getData();
            arrResp.add("[SUCCESS]");
            arrResp.add(response.get("actionSwarm"));
            return arrResp;
        } catch(Exception e) {
            if(arrResp.size() == 1) {
                arrResp.add("[FAILED]");
                arrResp.add(e.getMessage());
            }
            e.printStackTrace();
            return arrResp;
        }
    }


    public ArrayList<Object> requestSwarmsDetail(String restHost, String projectName, String swarmName, String token) {
        ArrayList<Object> arrResp = new ArrayList<>();
        arrResp.add(validateRestHost(restHost));
        try {
            JsonParser parser = new JsonParser();
            RestTemplate dgsRestTemplate = new RestTemplate();
            DefaultGraphQLClient graphQLClient = new DefaultGraphQLClient(validateRestHost(restHost));
            Map<String, String> swarmIdInput = new HashMap<>();
            swarmIdInput.put("projectName", projectName);
            swarmIdInput.put("swarmName", swarmName);
            Map<String, Object> variable = new HashMap<>();
            variable.put("input", swarmIdInput);
            variable.put("includeAttachStatus", false);
            String query = "query Swarm ($input: SwarmIdInput!, $includeAttachStatus: Boolean) {\n" +
                    "    swarm(input: $input, includeAttachStatus: $includeAttachStatus) {\n" +
                    "        swarmName\n" +
                    "        projectName\n" +
                    "        owner\n" +
                    "        preferredId\n" +
                    "        imageName\n" +
                    "        capacity\n" +
                    "        running\n" +
                    "        type\n" +
                    "        ciType\n" +
                    "        ciTrigger\n" +
                    "        workers {\n" +
                    "            workerId\n" +
                    "            workerName\n" +
                    "            status\n" +
                    "            owner\n" +
                    "            preferredId\n" +
                    "            imageUrl\n" +
                    "            imageName\n" +
                    "            projectName\n" +
                    "            type\n" +
                    "            accesses {\n" +
                    "                ssh\n" +
                    "                ide\n" +
                    "                ideProtocol\n" +
                    "                ideEnabled\n" +
                    "                ports {\n" +
                    "                    host\n" +
                    "                    type\n" +
                    "                    port\n" +
                    "                    targetPort\n" +
                    "                }\n" +
                    "            }\n" +
                    "            resources {\n" +
                    "                cpu\n" +
                    "                storage\n" +
                    "                memory\n" +
                    "            }\n" +
                    "            volumeMounts {\n" +
                    "                mountPath\n" +
                    "                name\n" +
                    "                subPath\n" +
                    "            }\n" +
                    "            metadata {\n" +
                    "                namespace\n" +
                    "                node\n" +
                    "                podName\n" +
                    "                podIp\n" +
                    "            }\n" +
                    "            swarm {\n" +
                    "                index\n" +
                    "                name\n" +
                    "            }        \n" +
                    "        }\n" +
                    "    }\n" +
                    "}";

            GraphQLResponse graphQLResponse = graphQLClient.executeQuery(query, variable, (url, headers, body) -> {
                /**
                 * The requestHeaders providers headers typically required to call a GraphQL endpoint, including the Accept and Content-Type headers.
                 * To use RestTemplate, the requestHeaders need to be transformed into Spring's HttpHeaders.
                 */
                HttpHeaders requestHeaders = new HttpHeaders();
                requestHeaders.add("Authorization", token);
                requestHeaders.add("Content-Type","application/json");
                requestHeaders.add("Connection","keep-alive");
                requestHeaders.add("x-api-key","32538e82-d0f2-4980-8412-59b0dbc0b341");
                UUID uuid = UUID.randomUUID();
                requestHeaders.add("msg-id",String.valueOf(uuid));
                requestHeaders.putAll(headers);

                /**
                 * Use RestTemplate to call the GraphQL service.
                 * The response type should simply be String, because the parsing will be done by the GraphQLClient.
                 */
                ResponseEntity<String> exchange = dgsRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity(body, requestHeaders), String.class);

                JsonObject jsonObject = (JsonObject) parser.parse(exchange.getBody());

                if(jsonObject.get("errors") != null) {
                    arrResp.add("[FAILED]");
                    arrResp.add(jsonObject.get("errors").toString());
                }

                /**
                 * Return a HttpResponse, which contains the HTTP status code and response body (as a String).
                 * The way to get these depend on the HTTP client.
                 */
                return new HttpResponse(exchange.getStatusCodeValue(), exchange.getBody());
            });
            Map<String, Object> response = graphQLResponse.getData();
            arrResp.add("[SUCCESS]");
            arrResp.add(response.get("swarm"));
            return arrResp;
        } catch(Exception e) {
            if(arrResp.size() == 1) {
                arrResp.add("[FAILED]");
                arrResp.add(e.getMessage());
            }
            e.printStackTrace();
            return arrResp;
        }
    }

    public ArrayList<Object> requestSwarmSleep(String restHost, String projectName, String swarmName, String token, int index) {
        ArrayList<Object> arrResp = new ArrayList<>();
        arrResp.add(validateRestHost(restHost));
        try {
            JsonParser parser = new JsonParser();
            RestTemplate dgsRestTemplate = new RestTemplate();
            DefaultGraphQLClient graphQLClient = new DefaultGraphQLClient(validateRestHost(restHost));
            Map<String, Object> swarmActionInput = new HashMap<>();
            Map<String, String> id = new HashMap<>();
            id.put("projectName", projectName);
            id.put("swarmName", swarmName);
            swarmActionInput.put("id", id);
            swarmActionInput.put("action", "SLEEP");
            swarmActionInput.put("index", index);
            Map<String, Object> variable = new HashMap<>();
            variable.put("input", swarmActionInput);
            String query = "mutation Swarm ($input: SwarmActionInput!) {\n" +
                    "    actionSwarm(input: $input) {\n" +
                    "        status\n" +
                    "        result\n" +
                    "    }\n" +
                    "}";

            GraphQLResponse graphQLResponse = graphQLClient.executeQuery(query, variable, (url, headers, body) -> {
                /**
                 * The requestHeaders providers headers typically required to call a GraphQL endpoint, including the Accept and Content-Type headers.
                 * To use RestTemplate, the requestHeaders need to be transformed into Spring's HttpHeaders.
                 */
                HttpHeaders requestHeaders = new HttpHeaders();
                requestHeaders.add("Authorization", token);
                requestHeaders.add("Content-Type","application/json");
                requestHeaders.add("Connection","keep-alive");
                requestHeaders.add("x-api-key","32538e82-d0f2-4980-8412-59b0dbc0b341");
                UUID uuid = UUID.randomUUID();
                requestHeaders.add("msg-id",String.valueOf(uuid));
                requestHeaders.putAll(headers);

                /**
                 * Use RestTemplate to call the GraphQL service.
                 * The response type should simply be String, because the parsing will be done by the GraphQLClient.
                 */
                ResponseEntity<String> exchange = dgsRestTemplate.exchange(url, HttpMethod.POST, new HttpEntity(body, requestHeaders), String.class);

                JsonObject jsonObject = (JsonObject) parser.parse(exchange.getBody());

                if(jsonObject.get("errors") != null) {
                    arrResp.add("[FAILED]");
                    arrResp.add(jsonObject.get("errors").toString());
                }

                /**
                 * Return a HttpResponse, which contains the HTTP status code and response body (as a String).
                 * The way to get these depend on the HTTP client.
                 */
                return new HttpResponse(exchange.getStatusCodeValue(), exchange.getBody());
            });
            Map<String, Object> response = graphQLResponse.getData();
            arrResp.add("[SUCCESS]");
            arrResp.add(response.get("actionSwarm"));
            return arrResp;
        } catch(Exception e) {
            if(arrResp.size() == 1) {
                arrResp.add("[FAILED]");
                arrResp.add(e.getMessage());
            }
            e.printStackTrace();
            return arrResp;
        }
    }
}
