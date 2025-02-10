package io.jenkins.plugins.bee;

import hudson.model.Queue;
import hudson.model.*;
import hudson.slaves.OfflineCause;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MonitorSwarm implements Runnable{
    final int number1 = 1;
    final int number2 = 2;

    private final int swarmSleepCheckTime = 10; // second
    private final int idleTime = 1; // minute
    private final int millisecondToMinute = 60000;
    private final int sleepTime = (1000*60)*1;

    SwarmLogDao logDao = new SwarmLogDao();
    SwarmLog logData = new SwarmLog();
    SwarmConnectorDao dao = new SwarmConnectorDao();

    public String getBeeServerName(String beeServerUrl){
        String[] urls = beeServerUrl.split("api.");
        urls = urls[1].split(".lge");
        return urls[0];
    }

    public void logSave(String log, String projectName, String swarmName, String beeServerUrl){
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        String beeServer = getBeeServerName(beeServerUrl);
        logData = logDao.getOrCreateObject(projectName, swarmName, beeServer);
        String oldLogs = logData.getLogs();
        if(oldLogs != null){
            sb.append(oldLogs);
            sb.append("\n");
        }
        sb.append("["+formatter.format(date)+"]");
        sb.append(log);

        logData.setLogs(sb.toString());
        logDao.save(logData, projectName, swarmName, getBeeServerName(beeServerUrl));
    }

    public void runError(String errorMsg){
        SwarmConnector swarmConnector = dao.getOrCreateObject();
        if(errorMsg.length() > 0){
            List<SwarmConnectorData> swarmList = swarmConnector.getSwarmList();
            int swarmSize = swarmList.size();
            for(int i=0; i<swarmSize; i++){
                SwarmConnectorData tmp = swarmList.get(i);
                logSave(errorMsg,
                        tmp.getProjectName(),
                        tmp.getSwarmName(),
                        tmp.getBeeRestUrl());
            }
        }
    }

    private void restSwarmsSleep() {
        final List<Queue.BuildableItem> list = Hudson.get().getQueue()
                .getBuildableItems();

        SwarmConnectorDao dao = new SwarmConnectorDao();
        Computer[] computers = Jenkins.get().getComputers();

        for(Computer computer : computers) {
            List<SwarmConnectorData> swarmList = dao.getOrCreateObject().getSwarmList();
            if(computer.isIdle() && !computer.getName().equals("") && computer.isOnline()) {
                String nodeName = computer.getName();
                Node node = computer.getNode();
                long idleStartTime = computer.getIdleStartMilliseconds();
                long systemTime = System.currentTimeMillis();

                long pastMinute = (systemTime - idleStartTime) / millisecondToMinute;

                if(pastMinute < idleTime) {
                    return;
                }

                for (SwarmConnectorData swarm : swarmList) {

                    if(swarm.isDisabled()) {
                        continue;
                    }

                    for (String swarmNodeName : swarm.getNodeNames()) {
                        if (swarmNodeName.equals(nodeName)) {

                            StringBuffer sb = new StringBuffer();
                            boolean removeNode = false;

                            if(!checkRestJob(swarm)) {
                                try {
                                    Set<hudson.model.labels.LabelAtom> labelsSet = node.getAssignedLabels();
                                    Iterator<hudson.model.labels.LabelAtom> it = labelsSet.iterator();

                                    int labelIndex = 0;

                                    while(it.hasNext()) {
                                        String label = String.valueOf(it.next());
                                        if(!computer.getName().equals(label)) {
                                            if(labelIndex == 0) {
                                                sb.append(label);
                                            } else {
                                                sb.append(" " + label);
                                            }
                                            labelIndex++;
                                        }
                                    }

                                    if(sb != null && sb.length() > 0) {
                                        node.setLabelString("");
                                        node.save();
                                        removeNode = true;
                                        String msg = String.format("[REMOVE NODE LABEL][NAME : %s][LABEL : %s]", node.getNodeName(), sb.toString());
                                        logSave(msg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());

                                        int index = Integer.parseInt(nodeName.substring(nodeName.lastIndexOf('_') + 1));
                                        sleepSwarm(computer, swarm, index);
                                    }

                                } catch(Exception e) {
                                    String errorMsg = String.format("[REST SWARMS SLEEP]] [ERROR] %s", SwarmLog.getPrintStackTrace(e));
                                    logSave(errorMsg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                                } finally {
                                    try {
                                        System.out.println("finally start");
                                        if(sb != null && sb.length() > 0) {
                                            while(removeNode) {
                                                System.out.println("removeNode");
                                                while (computer.isOffline()) {
                                                    System.out.println("computer.isOffline()");
                                                    node.setLabelString(sb.toString());
                                                    node.save();
                                                    removeNode = false;
                                                    String msg = String.format("[RESTORE NODE LABEL][NAME : %s][LABEL : %s]", node.getNodeName(), sb.toString());
                                                    logSave(msg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                                                    break;
                                                }
                                            }
                                        }
                                        System.out.println("finally end");
                                    } catch(Exception e) {
                                        String errorMsg = String.format("[REST SWARMS SLEEP]] [ERROR] %s", SwarmLog.getPrintStackTrace(e));
                                        logSave(errorMsg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    private void sleepSwarm(Computer computer, SwarmConnectorData swarm, int index) {
        BeeRestCommunication beeRest = new BeeRestCommunication();

        ArrayList<Object> resp = beeRest.requestSwarmsDetail(
                swarm.getBeeRestUrl(),
                swarm.getProjectName(),
                swarm.getSwarmName(),
                swarm.getToken()
        );

        if (resp.get(number1).toString().equalsIgnoreCase("[SUCCESS]")) {
            JSONObject jsonObject = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));
            JSONArray jsonWorkers = jsonObject.getJSONArray("workers");
            JSONObject worker = null;
            String target = swarm.getProjectName() + '-' + swarm.getSwarmName() + '-' + String.valueOf(index);
            for(int i=0; i<jsonWorkers.size(); i++) {
                JSONObject tempWorker = (JSONObject) jsonWorkers.get(i);
                if(tempWorker.get("workerName").equals(target)) {
                    worker = tempWorker;
                }
            }
            if ((worker.get("status").toString()).equalsIgnoreCase("Running")) {
                boolean nodeIdle = false;
                boolean restJobs = false;
                for (int i = 0; i < swarmSleepCheckTime; i++) {
                    try {
                        TimeUnit.SECONDS.sleep(1);

                        Computer[] computers = Jenkins.get().getComputers();

                        restJobs = checkRestJob(swarm);

                        for(Computer com : computers) {
                            if(com.getName().equals(computer.getName())) {
                                if(com.isIdle()) {
                                    nodeIdle = true;
                                } else {
                                    nodeIdle = false;
                                }
                            }
                        }
                    } catch (Exception e) {
                        String errorMsg = String.format("[REST SWARMS SLEEP]] [ERROR] %s", SwarmLog.getPrintStackTrace(e));
                        logSave(errorMsg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                    }
                }
                if (nodeIdle && !restJobs) {
                    ArrayList<Object> swarmSleepResult = beeRest.requestSwarmSleep(
                            swarm.getBeeRestUrl(),
                            swarm.getProjectName(),
                            swarm.getSwarmName(),
                            swarm.getToken(),
                            index
                    );

                    StringBuffer logMsg = new StringBuffer();

                    if (swarmSleepResult.get(1).toString().equalsIgnoreCase("[SUCCESS]")) {

                        JSONObject actionSwarm = JSONObject.fromObject(swarmSleepResult.get(number2));

                        if(actionSwarm.get("status").toString().equalsIgnoreCase("success")) {
                            computer.disconnect(new OfflineCause.ChannelTermination(new Exception("sleep swarm")));
                            swarm.setIndex(index);
                            logMsg.append("[SLEEP SWARM REQUEST SUCCESS]");
                            logMsg.append("[PROJECT : " + swarm.getProjectName() + "]");
                            logMsg.append("[SWARM : " + swarm.getSwarmName() + "]");
                            logMsg.append("[INDEX : " + Integer.valueOf(swarm.getIndex()) + "]");
                            logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                        }
                    } else {
                        logMsg.append("[SLEEP SWARM REQUEST FAIL]");
                        logMsg.append("[PROJECT : " + swarm.getProjectName() + "]");
                        logMsg.append("[SWARM : " + swarm.getSwarmName() + "]");
                        logMsg.append("[INDEX : " + Integer.valueOf(swarm.getIndex()) + "]");
                        logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                    }
                }
            }
        }
    }

    public boolean checkRestJob(SwarmConnectorData swarm) {
        boolean restJobs = false;

        final List<Queue.BuildableItem> list = Hudson.get().getQueue()
                .getBuildableItems();

        String labels = swarm.getLabels();

        for (Queue.BuildableItem buildableItem : list) {
            for(Label l : buildableItem.task.getAssignedLabel().listAtoms()){
                if(labels.contains(String.valueOf(l))) {
                    restJobs = true;
                }
            }
        }
        return restJobs;
    }

    @Override
    public void run() {
        while(!Thread.currentThread().isInterrupted()){

            String errorMsg = "";

            try {
                restSwarmsSleep();
            } catch (Exception e){
                errorMsg = String.format("[REST SWARMS SLEEP]] [ERROR] %s", SwarmLog.getPrintStackTrace(e));
                runError(errorMsg);
            }

            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
                errorMsg = String.format("[REST SWARMS SLEEP] [Thread Sleep ERROR] %s", SwarmLog.getPrintStackTrace(e));
                runError(errorMsg);
            }
        }
    }
}
