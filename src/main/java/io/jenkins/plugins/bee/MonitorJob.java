package io.jenkins.plugins.bee;

import hudson.model.*;
import hudson.model.Queue;
import hudson.model.labels.LabelExpression;
import hudson.slaves.OfflineCause;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MonitorJob implements Runnable {
    public interface Callback{
        public void onDetectedQueue( ArrayList<HashMap<String, ArrayList<String>>> jobList, String error);
    }

    final int number1 = 1;
    final int number2 = 2;

    private final int swarmSleepCheckTime = 10; // second
    private final int idleTime = 20; // minute
    private final int millisecondToMinute1 = 60000;
    private final int millisecondToMinute2 = 60;
    private final int sleepTime = (1000*60)*1;
    private Callback callBack;
    private HashMap<String, Integer> mJobName = new HashMap<>();

    SwarmLogDao logDao = new SwarmLogDao();
    SwarmLog logData = new SwarmLog();
    SwarmConnectorDao dao = new SwarmConnectorDao();

    public Callback getCallBack() { return callBack; }
    public void setCallBack(Callback callBack) { this.callBack = callBack; }

    public String getBuildJobName(int nextBuilderNumber, String jobName){
        String buildJobName = "";
        int nBuilderNumber = nextBuilderNumber;
        Integer number = mJobName.get(jobName);
        if(number != null){
            nBuilderNumber = number.intValue()+1;
            mJobName.put(jobName, nBuilderNumber);
        }else{
            mJobName.put(jobName, nBuilderNumber);
        }
        buildJobName = String.format("%s#%d", jobName, nBuilderNumber);
        return buildJobName;
    }

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

    private void retrieveQueueJobs() {
        // getInstance deprecated is changed to get function.
        final List<Queue.BuildableItem> list = Hudson.get().getQueue()
                .getBuildableItems();
        ArrayList<HashMap<String, ArrayList<String>>> jobList = new ArrayList<>();
        mJobName = new HashMap<>();

        for (Queue.BuildableItem buildableItem : list) {
            HashMap<String, ArrayList<String>> jobLabel = new HashMap<>();
            ArrayList<String> arrLabels = new ArrayList<String>();

            try {
                for(Label l : buildableItem.task.getAssignedLabel().listAtoms()){
                    arrLabels.add(String.valueOf(l));
                }
            } catch (Exception e) {
                arrLabels.add(String.valueOf(buildableItem.getAssignedLabel()));
            }

            String jobName = "";
            Job job = (Job) Jenkins.get().getItemByFullName(Jenkins.get().getRootDir()+ File.separator+"jobs"+File.separator+buildableItem.task.getName());
            try{
                jobName = getBuildJobName(job != null ? job.getNextBuildNumber() : 1, buildableItem.task.getName());
            }catch (Exception e){
                jobName = getBuildJobName(1, buildableItem.task.getName());
                e.printStackTrace();
            }

            jobLabel.put(jobName, arrLabels);
            jobList.add(jobLabel);
        }
        this.callBack.onDetectedQueue(jobList, null);
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

                long pastMinute = ((systemTime - idleStartTime) / millisecondToMinute1) % millisecondToMinute2;

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
                            boolean removeNodeLabel = false;
                            boolean sleepSwarm = false;

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
                                        removeNodeLabel = true;
                                        String msg = String.format("[REMOVE NODE LABEL][NAME : %s][LABEL : %s]", node.getNodeName(), sb.toString());
                                        logSave(msg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());

                                        int index = Integer.parseInt(nodeName.substring(nodeName.lastIndexOf('_') + 1));
                                        sleepSwarm = sleepSwarm(computer, swarm, index);
                                    }
                                } catch (Exception e) {
                                    String errorMsg = String.format("[REST SWARMS SLEEP]] [ERROR] %s", SwarmLog.getPrintStackTrace(e));
                                    logSave(errorMsg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                                } finally {
                                    try {
                                        if(sb != null && sb.length() > 0) {
                                            while(removeNodeLabel) {
                                                while (computer.isOffline() || !sleepSwarm) {;
                                                    node.setLabelString(sb.toString());
                                                    node.save();
                                                    removeNodeLabel = false;
                                                    String msg = String.format("[RESTORE NODE LABEL][NAME : %s][LABEL : %s]", node.getNodeName(), sb.toString());
                                                    logSave(msg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                                                    break;
                                                }
                                            }
                                        }
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

    private boolean sleepSwarm(Computer computer, SwarmConnectorData swarm, int index) {
        BeeRestCommunication beeRest = new BeeRestCommunication();
        boolean result = false;

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
                    if (swarmSleepResult.get(1).toString().equalsIgnoreCase("[SUCCESS]")) {

                        JSONObject actionSwarm = JSONObject.fromObject(swarmSleepResult.get(number2));

                        if(actionSwarm.get("status").toString().equalsIgnoreCase("success")) {
                            computer.disconnect(new OfflineCause.ChannelTermination(new Exception("sleep swarm")));
                            swarm.setIndex(index);
                            StringBuffer logMsg = new StringBuffer();
                            logMsg.append("[SLEEP SWARM REQUEST SUCCESS]");
                            logMsg.append("[PROJECT : " + swarm.getProjectName() + "]");
                            logMsg.append("[SWARM : " + swarm.getSwarmName() + "]");
                            logMsg.append("[INDEX : " + Integer.valueOf(swarm.getIndex()) + "]");
                            logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                            result =  true;
                        }
                    }
                }
            }
        }
        return result;
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
                retrieveQueueJobs();
                restSwarmsSleep();
            } catch (Exception e){
                errorMsg = String.format("[Queue Detected] [ERROR] %s", SwarmLog.getPrintStackTrace(e));
                this.callBack.onDetectedQueue(null, errorMsg);
            }

            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
                errorMsg = String.format("[Queue Detected] [Thread Sleep ERROR] %s", SwarmLog.getPrintStackTrace(e));
                this.callBack.onDetectedQueue(null, errorMsg);
            }
        }
    }
}
