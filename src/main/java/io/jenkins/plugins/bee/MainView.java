package io.jenkins.plugins.bee;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Node.Mode;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.plugins.sshslaves.verifiers.SshHostKeyVerificationStrategy;
import hudson.slaves.*;
import jenkins.model.Jenkins;
import net.sf.json.JSONSerializer;
import org.jvnet.hudson.reactor.ReactorException;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.File;
import java.io.IOException;
import java.util.*;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import hudson.plugins.sshslaves.SSHLauncher;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.CronScheduleBuilder.cronSchedule;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import javax.servlet.ServletException;

@Extension
public class MainView implements RootAction {
    private final static Logger LOG = Logger.getAnonymousLogger();
    private boolean connectStatus = true;

    final int number1 = 1;
    final int number2 = 2;
    final int number3 = 3;

    final int mNodeTimeout = 60;
    final int mNodeMaximum = 10;
    final int mNodeWaitBetween = 15;

    ObjectMapper mapper = new ObjectMapper();
    SwarmConnectorDao dao = new SwarmConnectorDao();
    SwarmConnector swarm_connector = null;
    SwarmConnectorData swarmData = new SwarmConnectorData();
    SwarmLogDao logDao = new SwarmLogDao();
    SwarmLog logData = new SwarmLog();
    MonitorJob monitorJob = new MonitorJob();
    MonitorSwarm monitorSwarm = new MonitorSwarm();
    BeeRestCommunication beeRest;

    List<String> m_nodes = null;

    HashMap<String, ScaleOutData> mScaleOutList = new HashMap<>();
    Thread detectedThread = null;
    Thread swarmSleepThread = null;
    MonitorJob.Callback m_callback = null;

    public class ScaleOutData {
        String swarmName;
        int workerNodeNum;

        ScaleOutData(String swarmName, int workerNodeNum) {
            this.swarmName = swarmName;
            this.workerNodeNum = workerNodeNum;
        }
    }

    public MainView() {

        runCheckLabel();
        beeRest = new BeeRestCommunication();

        /**
         * 플러그인 이 생성되면서 스웜로그 저장을 위한 폴더를 생성하는 코드
         * 작성자 : osckorea 김윤규
         */
        String logPath = Jenkins.get().getRootDir() +File.separator+ "logs"+File.separator+"swarmLogs";
        File folder = new File(logPath);

        if (!folder.exists()) {
            try{
                folder.mkdir();
                LOG.info("=== SWARM logs folder created ===");
            }catch(Exception e){
                e.getStackTrace();
            }
        }else{
            LOG.info("=== SWARM logs folder already exists ===");
        }

        /**
         * 한달 이상된 로그 삭제를 위한 스케줄러 실행
         * : 매일 오전 5시 정각 진행
         * 작성자 : osckorea 김윤규
         */

        SchedulerFactory schedulerFactory = new StdSchedulerFactory();

        try {
            Scheduler scheduler = schedulerFactory.getScheduler();

            JobDetail job = newJob(RemoveLogJob.class)
                    .withIdentity("jobName", Scheduler.DEFAULT_GROUP)
                    .build();

            Trigger trigger = newTrigger()
                    .withIdentity("triggerName", Scheduler.DEFAULT_GROUP)
                    .withSchedule(cronSchedule("0 0 5 * * ?"))
                    .build();

            scheduler.scheduleJob(job, trigger);
            scheduler.start();

        } catch (SchedulerException e) {
            e.printStackTrace();
        }

    }

    @Override
    public String getIconFileName() {
        if(Hudson.getInstanceOrNull().hasPermission(Jenkins.ADMINISTER)){
            return "document.png";
        }else{
            return null;
        }
    }

    @Override
    public String getDisplayName() {
        if(Hudson.getInstanceOrNull().hasPermission(Jenkins.ADMINISTER)){
            return "Bee Swarm Connector";
        }else{
            return null;
        }
    }

    @Override
    public String getUrlName() {
        return "Bee-Connector";
    }

    public SwarmConnector getSwarmConnector() {
        swarm_connector = dao.getOrCreateObject();
        return swarm_connector;
    }

    public SwarmConnectorData getSwarmData(){
        return this.swarmData;
    }

    public ArrayList<CredentialsBee> getCredentials() {
        ArrayList<CredentialsBee> arrlist = new ArrayList<CredentialsBee>();
        SystemCredentialsProvider s = SystemCredentialsProvider.getInstance();

        for (Credentials c : s.getCredentials()) {
            CredentialsBee credentialsBee = new CredentialsBee();
            String description = "";
            String id = "";
            String userName = "";
            try{

                String className = c.getClass().getSimpleName();
                if(className.equalsIgnoreCase("UsernamePasswordCredentialsImpl")){
                    UsernamePasswordCredentialsImpl userImpl = ((UsernamePasswordCredentialsImpl) c);
                    description = userImpl.getDescription();
                    id = userImpl.getId();
                    userName = userImpl.getUsername();

                }else if(className.equalsIgnoreCase("BasicSSHUserPrivateKey")){
                    BasicSSHUserPrivateKey userImpl = ((BasicSSHUserPrivateKey) c);
                    description = userImpl.getDescription();
                    id = userImpl.getId();
                    userName = userImpl.getUsername();

                }
            }catch (Exception e){
                description = "";
                id = e.getMessage();
                userName = "";
            }finally {
                if(!userName.equalsIgnoreCase("")){
                    credentialsBee.setDescription(description);
                    credentialsBee.setId(id);
                    credentialsBee.setUserName(userName);
                    credentialsBee.setDisplayName(userName, description);
                    arrlist.add(credentialsBee);
                }

            }
        }
        return arrlist;
    }

    public String getBeeServerName(String beeServerUrl){

        String[] urls = beeServerUrl.split("api.");
        urls = urls[1].split(".lge");
        return urls[0];
    }

    public String getNodeName(String projectName, String swarmName, String beeServerUrl, int index){
        return String.format("%s_%s_%s_%d", projectName, swarmName, getBeeServerName(beeServerUrl), index);
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

    public void removeNode(String nodeName) throws IOException {
        DumbSlave dumb = (DumbSlave) Jenkins.get().getNode(nodeName);
        Jenkins.get().removeNode(dumb);
    }

    public void removeNodeSlave(SwarmConnectorData swarm){

        StringBuilder logMsg = new StringBuilder();
        logMsg.append("[DELETE][NODE]");
        try {
            List<String> nodeNames = swarm.getNodeNames();
            for(int i=0; i<nodeNames.size(); i++){
                removeNode(nodeNames.get(i));
                logMsg.append("[SUCCESS]");
                logMsg.append("[" + nodeNames.get(i) + "]");
            }
            logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
        }catch (Exception e) {
            logMsg.append("[FAILED] ");
            logMsg.append(e.getMessage());
            logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
        }
    }

    public String updateNodeSlave(String nodeName,
                                  String labels,
                                  String credential,
                                  String sshUrl,
                                  String sshPort,
                                  String projectName,
                                  String swarmName,
                                  String beeServerUrl,
                                  String javaPath) throws IOException {

        StringBuilder logMsg = new StringBuilder();
        DumbSlave dumb = (DumbSlave) Jenkins.get().getNode(nodeName);
        if(dumb == null){
            logMsg.append("[NODE][FAILED] ");
            logMsg.append( String.format("NOT FIND NODE : %s", nodeName));
            logSave(logMsg.toString(), projectName, swarmName, beeServerUrl);
            return logMsg.toString();
        }else{

            SshHostKeyVerificationStrategy hostKeyVerificationStrategy = new NonVerifyingKeyVerificationStrategy();
            ComputerLauncher launcher = new SSHLauncher( sshUrl,
                    Integer.parseInt(sshPort),
                    credential,
                    (String)null,
                    javaPath,
                    (String)null,
                    (String)null,
                    mNodeTimeout,
                    mNodeMaximum,
                    mNodeWaitBetween,
                    hostKeyVerificationStrategy);

            dumb.setLauncher(launcher);
            dumb.setLabelString(labels);
            dumb.save();
            logMsg.append("[NODE][SUCCESS] ");
            logMsg.append(nodeName);
            return logMsg.toString();
        }
    }

    public String saveNodeSlave(String sshUrl,
                                String sshPort,
                                String credential,
                                String swarmName,
                                String projectName,
                                String executors,
                                String remoteDir,
                                int index,
                                String labels,
                                String beeServerUrl,
                                String javaPath){
        StringBuilder logMsg = new StringBuilder();
        DumbSlave dumb = null;
        String dumbName = getNodeName(projectName, swarmName, beeServerUrl, index);
        try {
            SshHostKeyVerificationStrategy hostKeyVerificationStrategy = new NonVerifyingKeyVerificationStrategy();
            ComputerLauncher launcher = new SSHLauncher( sshUrl,
                    Integer.parseInt(sshPort),
                    credential,
                    (String)null,
                    javaPath,
                    (String)null,
                    (String)null,
                    mNodeTimeout,
                    mNodeMaximum,
                    mNodeWaitBetween,
                    hostKeyVerificationStrategy);
            dumb = new DumbSlave(dumbName, remoteDir, launcher);
            dumb.setLabelString(labels);
            dumb.setNumExecutors(Integer.parseInt(executors));
            dumb.setMode(Mode.EXCLUSIVE);
            dumb.setRetentionStrategy(RetentionStrategy.INSTANCE);

            logMsg.append("[NODE][SUCCESS] ");
            logMsg.append(dumbName);
            Jenkins.get().addNode(dumb);
            logSave(logMsg.toString(), projectName, swarmName, beeServerUrl);
            return dumbName;
        }
        catch (Exception e) {
            logSave("[NODE][FAILED][" + dumbName + "] " + SwarmLog.getPrintStackTrace(e), projectName, swarmName, beeServerUrl);
            return null;
        }
    }

    public String removeSpecialWord(String target, String removeWord){
        String tmp = target.replaceFirst(removeWord, "");
        return tmp;
    }

    public String getRootPath(String reqUri){
        String[] uri = reqUri.split("/");
        StringBuilder uriPath = new StringBuilder();
        String rePath = "/";

        for(int i=0; i<(uri.length-1); i++){
            if(!uri[i].equalsIgnoreCase("")){
                uriPath.append("/");
                uriPath.append(uri[i]);
            }
        }
        if(uriPath.length() > 0){
            rePath = uriPath.toString();
        }
        return rePath;
    }

    public SwarmCIWorkerData getCIWorkerData(JSONObject accesses, String nodeName, int index){
        SwarmCIWorkerData sdata = new SwarmCIWorkerData();
        String[] ssh = accesses.getString("ssh").split(" ");
        sdata.setNodeName(nodeName);
        sdata.setSshPort(ssh[number2]);
        sdata.setSshUrl(ssh[number3].split("@")[number1]);
        sdata.setIndex(index);
        return sdata;
    }


    public HashMap<String, ArrayList<SwarmCIWorkerData>> GetNodes(JSONArray jsonWorkers, SwarmConnectorData pSwarmData){
        HashMap<String, ArrayList<SwarmCIWorkerData>> dicNodeName = new HashMap<>();
        ArrayList<SwarmCIWorkerData> arrRemove = new ArrayList<>();
        ArrayList<SwarmCIWorkerData> arrAdd = new ArrayList<>();
        ArrayList<String> nodes = (ArrayList<String>) pSwarmData.getNodeNames();

        if(nodes == null){
            nodes = new ArrayList<>();
        }
        m_nodes = new ArrayList<>();
        boolean bMatching = false;
        for(int i = 0; i < jsonWorkers.size(); i++) {
            JSONObject obj = (JSONObject) jsonWorkers.get(i);
            JSONObject swarmObject = obj.getJSONObject("swarm");
            String nodeName = getNodeName(pSwarmData.getProjectName(),
                    pSwarmData.getSwarmName(),
                    pSwarmData.getBeeRestUrl(),
                    swarmObject.getInt("index"));
            m_nodes.add(nodeName);
            bMatching = false;

            for (String nodeTmp : nodes) {
                if(Jenkins.get().getNode(nodeName) == null) {
                    bMatching = false;
                    break;
                }

                if (nodeTmp.equalsIgnoreCase(nodeName)) {
                    bMatching = true;
                    break;
                }
            }
            if(bMatching == false){
                arrAdd.add(getCIWorkerData(obj.getJSONObject("accesses"), nodeName, swarmObject.getInt("index")));
            }

        }
        for (String nodeTmp : nodes) {
            bMatching = false;
            for(int i = 0; i < jsonWorkers.size(); i++) {
                JSONObject obj = (JSONObject) jsonWorkers.get(i);
                JSONObject swarmObject = obj.getJSONObject("swarm");
                String nodeName = getNodeName(pSwarmData.getProjectName(),
                        pSwarmData.getSwarmName(),
                        pSwarmData.getBeeRestUrl(),
                        swarmObject.getInt("index"));
                if (nodeName.equalsIgnoreCase(nodeTmp)) {
                    bMatching = true;
                    break;
                }
            }
            if(bMatching == false){
                SwarmCIWorkerData sdata = new SwarmCIWorkerData();
                sdata.setNodeName(nodeTmp);
                arrRemove.add(sdata);
            }
        }

        dicNodeName.put("add", arrAdd);
        dicNodeName.put("remove", arrRemove);
        return dicNodeName;
    }

    public void updateCapacity(SwarmConnectorData pSwarmData, int capacity, List<String> nodes){
        SwarmConnectorData swarm = pSwarmData;
        if(Integer.parseInt(swarm.getCapacity()) != capacity){
            List<SwarmConnectorData> arrSwarm = new ArrayList<SwarmConnectorData>();
            for(int i=0; i<swarm_connector.getSwarmList().size(); i++){
                SwarmConnectorData tmp = swarm_connector.getSwarmList().get(i);
                if( tmp.getSwarmName().equalsIgnoreCase(swarm.getSwarmName()) &&
                        tmp.getProjectName().equalsIgnoreCase(swarm.getProjectName()) &&
                        tmp.getBeeRestUrl().equalsIgnoreCase(swarm.getBeeRestUrl())){
                    swarm.setCapacity( String.valueOf(capacity));
                    swarm.setNodeNames(nodes);
                    arrSwarm.add(swarm);
                }else{
                    arrSwarm.add(tmp);
                }
            }
            swarm_connector.setSwarmList(arrSwarm);
            try {
                dao.save(swarm_connector);
                logSave("[UPDATE][CAPACITY & NODE-NAMES][SUCCESS] ",
                        swarm.getProjectName(),
                        swarm.getSwarmName(),
                        swarm.getBeeRestUrl());
            } catch (Exception e) {
                logSave("[UPDATE][CAPACITY & NODE-NAMES][Failed] "+SwarmLog.getPrintStackTrace(e),
                        swarm.getProjectName(),
                        swarm.getSwarmName(),
                        swarm.getBeeRestUrl());
                e.printStackTrace();
            }
        }
    }

    public void swarmSync(JSONArray jsonWorkers,  SwarmConnectorData pSwarmData) throws IOException {

        StringBuffer logMsg = new StringBuffer();
        logMsg.append("[SWARMS SYNCHRONIZATION][START] ");
        HashMap<String, ArrayList<SwarmCIWorkerData>> dicNodeName = GetNodes(jsonWorkers, pSwarmData);
        List<SwarmCIWorkerData> arrAdd = dicNodeName.get("add");
        List<SwarmCIWorkerData> arrRemove = dicNodeName.get("remove");


        for(SwarmCIWorkerData ciDataAdd: arrAdd){
            saveNodeSlave(ciDataAdd.getSshUrl(), ciDataAdd.getSshPort(), pSwarmData.getCredential(),
                    pSwarmData.getSwarmName(), pSwarmData.getProjectName(), pSwarmData.getExecutors(),
                    pSwarmData.getRemoteDirectory(), ciDataAdd.getIndex(),
                    pSwarmData.getLabels(), pSwarmData.getBeeRestUrl(), pSwarmData.getJavaPath());
        }
        for(SwarmCIWorkerData ciDataRemove: arrRemove){
            logMsg.append("[REMOVE NODE] " + ciDataRemove.getNodeName());
            this.removeNode(ciDataRemove.getNodeName());
        }
        logSave(logMsg.toString(), swarmData.getProjectName(), pSwarmData.getSwarmName(), pSwarmData.getBeeRestUrl());
        updateCapacity(pSwarmData, jsonWorkers.size(), m_nodes);
        logMsg.append("[SWARMS SYNCHRONIZATION][END]");
    }

    public ArrayList<String> processAddNode(JSONArray jsonWorkers,  SwarmConnectorData pSwarmData){

        ArrayList<String> nodeNames = new ArrayList<>();
        for(int i = 0; i < jsonWorkers.size(); i++){
            JSONObject obj = (JSONObject)jsonWorkers.get(i);
            JSONObject accesses = obj.getJSONObject("accesses");
            JSONObject swarmObject = obj.getJSONObject("swarm");
            String[] ssh = accesses.getString("ssh").split(" ");
            String sshUrl = ssh[number3].split("@")[number1];
            String sshPort = ssh[number2];
            String nodeName = saveNodeSlave(sshUrl, sshPort, pSwarmData.getCredential(),
                    pSwarmData.getSwarmName(), pSwarmData.getProjectName(), pSwarmData.getExecutors(),
                    pSwarmData.getRemoteDirectory(),  swarmObject.getInt("index"),
                    pSwarmData.getLabels(), pSwarmData.getBeeRestUrl(), pSwarmData.getJavaPath());
            if(nodeName != null){
                nodeNames.add(nodeName);
            }
        }

        return nodeNames;
    }

    public String processEditNode(JSONArray jsonWorkers,  SwarmConnectorData pSwarmData) throws IOException, ReactorException, InterruptedException {
        StringBuffer logMsg = new StringBuffer();
        for(int i = 0; i < jsonWorkers.size(); i++){
            JSONObject obj = (JSONObject)jsonWorkers.get(i);
            JSONObject accesses = obj.getJSONObject("accesses");
            JSONObject swarmObject = obj.getJSONObject("swarm");
            String nodeName = getNodeName(pSwarmData.getProjectName(),
                    pSwarmData.getSwarmName(),
                    pSwarmData.getBeeRestUrl(),
                    swarmObject.getInt("index"));
            String[] ssh = accesses.getString("ssh").split(" ");
            String sshUrl = ssh[number3].split("@")[number1];
            String sshPort = ssh[number2];

            String log = updateNodeSlave(nodeName,
                    pSwarmData.getLabels(),
                    pSwarmData.getCredential(),
                    sshUrl,
                    sshPort,
                    pSwarmData.getProjectName(),
                    pSwarmData.getSwarmName(),
                    pSwarmData.getBeeRestUrl(),
                    pSwarmData.getJavaPath());
            logMsg.append(log);
        }
        Jenkins.get().reload();
        return logMsg.toString();
    }

    public boolean isDuplication(String projectName, String swarmName, String beeServer){
        List<SwarmConnectorData> swarmList =  swarm_connector.getSwarmList();
        for(int i=0; i<swarmList.size(); i++){
            SwarmConnectorData data = swarmList.get(i);
            if(data.getSwarmName().equalsIgnoreCase(swarmName)
                    && data.getProjectName().equalsIgnoreCase(projectName)
                    && data.getBeeRestUrl().equalsIgnoreCase(beeServer)){
                return true;
            }
        }
        return false;
    }

    /**
     * 함수명 : doSyncAll
     * 작성자 : 정승진
     * 수정 : 김윤규
     * 내용 : 전체 스웜에 대한 Capacity 진행
     * @param request
     * @param response
     */

    public void doSyncAll(final StaplerResponse response) throws IOException {
        StringBuffer logMsg = new StringBuffer();
        SwarmConnectorData swarm = null;
        boolean isSyncSuccess = false;

        try {
            for(int i=0; i<swarm_connector.getSwarmList().size(); i++){
                swarm = swarm_connector.getSwarmList().get(i);
                logMsg.setLength(0);
                logMsg.append(swarm.getProjectName());

                ArrayList<Object> resp = beeRest.requestSwarmsDetail(swarm.getBeeRestUrl(),
                        swarm.getProjectName(),
                        swarm.getSwarmName(),
                        swarm.getToken());

                if(resp.get(1).toString().equalsIgnoreCase("[SUCCESS]")){
                    JSONObject jsonObject = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));
                    JSONArray jsonWorkers = jsonObject.getJSONArray("workers");

                    //swarm sync
                    this.swarmSync(jsonWorkers, swarm);
                    Jenkins.get().reload();

                    isSyncSuccess = true;
                }else{
                    isSyncSuccess = false;
                    logMsg.append("[FAILED] : " + resp.get(number2) + "]");
                }

                logForSync(isSyncSuccess, logMsg, swarm);
            }
        } catch (IOException | InterruptedException | ReactorException e) {
            logMsg.append(SwarmLog.getPrintStackTrace(e));
            logForSync(isSyncSuccess, logMsg, swarm);
        } finally {
            mScaleOutList = new HashMap<>(); // Sync 진행 후 큐에 있던 job 들을 모두 비운다.
            response.getWriter().write("[END][SYNC-CAPACITY]");
            response.getWriter().close();
        }
    }

    /**
     * 함수명 : doSync
     * 작성자 : 김윤규
     * 내용 : 개별 스웜에 대한 Capacity sync 진행
     * @param request
     * @param response
     */
    public void doSync(final StaplerRequest request, final StaplerResponse response) throws IOException, ReactorException, InterruptedException {

        String swarmName = request.getParameter("swarmName");

        StringBuffer logMsg = new StringBuffer();
        SwarmConnectorData swarm = null;
        boolean isSyncSuccess = false;

        for(int i=0; i<swarm_connector.getSwarmList().size(); i++){
            if(swarm_connector.getSwarmList().get(i).getSwarmName().equals(swarmName)){
                swarm = swarm_connector.getSwarmList().get(i);
            }
        }

        if(swarm != null){
            logMsg.append(swarm.getProjectName());

            ArrayList<Object> resp = beeRest.requestSwarmsDetail(swarm.getBeeRestUrl(),
                    swarm.getProjectName(),
                    swarm.getSwarmName(),
                    swarm.getToken());

            if(resp.get(1).toString().equalsIgnoreCase("[SUCCESS]")){
                JSONObject jsonObject = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));
                JSONArray jsonWorkers = jsonObject.getJSONArray("workers");

                //swarm sync
                this.swarmSync(jsonWorkers, swarm);
                Jenkins.get().reload();

                isSyncSuccess = true;
            }else{
                logMsg.append("[FAILED] : " + resp.get(number2) + "]");
                isSyncSuccess = false;
            }

            List<String> workerNodes = null;

            // mScaleOutList에 현재 대기중인 job, 그리고 그 job을 실행할 worker 노드이름과 현재 스웜의 이름을 비교
            for (String key : mScaleOutList.keySet()){

                workerNodes = swarm.getNodeNames();

                for(int k=0; k < workerNodes.size(); k++){
                    if(mScaleOutList.get(key).swarmName.equals(swarm.getProjectName()+"-"+swarm.getSwarmName()+"-"+(k+1))){
                        mScaleOutList.remove(key); //해당 job을 remove
                    }
                }
            }

            logForSync(isSyncSuccess, logMsg, swarm);
            response.getWriter().write("[END][SYNC-CAPACITY]" + swarm.getSwarmName());

        }else{
            //swarm이 없을 경우 리턴할 메세지
            response.getWriter().write("Not exists " + swarmName);
        }

        response.getWriter().close();
    }

    /**
     * 함수명 : lofForSync
     * 작성자 : 오에스씨코리아 김윤규
     * 내용 : doSyncAll, doSync 함수 안에서 중복되는 로그입력 코드를 함수화
     * @param isSuccess : sync 성공 여부
     * @param logMsg : 로그 메세지
     * @param swarm : 각 스웜
     * @throws IOException
     */
    public void logForSync (boolean isSuccess, StringBuffer logMsg, SwarmConnectorData swarm) {

        String log = "";

        if(isSuccess){
            log = "[SUCCESS][SYNC-CAPACITY]";
        }else{
            log = "[FAILED][SYNC-CAPACITY]";
        }

        log = log + logMsg.toString();

        logSave(log, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
    }


    public void doSave(final StaplerRequest request, final StaplerResponse response) throws IOException {
        if(swarm_connector == null){
            getSwarmConnector();
        }
        JSONObject form = null;
        StringBuilder logMsg = new StringBuilder();
        StringBuilder logErrMsg = new StringBuilder();
        Boolean bError = false;
        String params = request.getParameter("params");
        form = JSONObject.fromObject(JSONSerializer.toJSON(params));
        String projectName = removeSpecialWord(form.getString("project_name"), "\t");
        String swarmName = removeSpecialWord(form.getString("swarm_name"), "\t");
        String ownerName = removeSpecialWord(form.getString("owner_name"), "\t");
        String beeServer = removeSpecialWord(form.getString("beeRestUrl"), "\t");
        String token = removeSpecialWord(form.getString("token"), "\t");
        String remoteDirectory = form.getString("node_remote_path");
        String credential = form.getString("select_credentials");
        String labels = form.getString("labels");
        String javaPath = removeSpecialWord(form.getString("java_path"), "\t");

        try {

            logSave("[CREATE][START]", projectName, swarmName, beeServer);
            if(isDuplication(projectName, swarmName, beeServer)){
                bError = true;
                logErrMsg.append("[FAILED]");
                logErrMsg.append("Duplication project name, swarm name");
            }else{
                SwarmConnectorData swarm = new SwarmConnectorData();
                swarm.setProjectName(projectName);
                swarm.setSwarmName(swarmName);
                swarm.setOwnerName(ownerName);
                swarm.setToken(token);
                swarm.setBeeRestUrl(beeServer);
                swarm.setRemoteDirectory(remoteDirectory);
                swarm.setExecutors("1");
                swarm.setCredential(credential);
                swarm.setLabels(labels);
                swarm.setJavaPath(javaPath);

                ArrayList<Object> resp = beeRest.requestSwarmsDetail(swarm.getBeeRestUrl(),
                        swarm.getProjectName(),
                        swarm.getSwarmName(),
                        swarm.getToken());

                if(resp.get(1).toString().equalsIgnoreCase("[SUCCESS]")){
                    JSONObject jsonObject = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));

                    logMsg.append("[CREATE]");
                    logErrMsg.append("[CREATE]");

                    swarm.setImageName(jsonObject.getString("imageName"));
                    swarm.setCapacity(String.format("%d", jsonObject.getInt("capacity")));
                    //Create nodes
                    swarm.setNodeNames(processAddNode(jsonObject.getJSONArray("workers"), swarm));

                    ArrayList<SwarmConnectorData> arrSwarm;
                    if(swarm_connector == null){
                        getSwarmConnector();
                    }
                    arrSwarm = (ArrayList<SwarmConnectorData>)swarm_connector.getSwarmList();
                    if(arrSwarm == null){
                        arrSwarm = new ArrayList<SwarmConnectorData>();
                    }
                    arrSwarm.add(swarm);
                    swarm_connector.setSwarmList(arrSwarm);
                    dao.save(swarm_connector);
                    logMsg.append("[SUCCESS]");
                }else{
                    bError = true;
                    logErrMsg.append("[FAILED] : " + resp.get(number2) + "]");
                }
            }

        } catch (ServletException e) {
            bError = true;
            logErrMsg.append("[FAILED][ServletException]");
            logErrMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        } catch (IOException e) {
            bError = true;
            logErrMsg.append("[FAILED][IOException]");
            logErrMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        } catch (Exception e) {
            bError = true;
            logErrMsg.append("[FAILED][Exception]");
            logErrMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        }finally {
            if(bError){
                response.getWriter().write(logErrMsg.toString());
                response.getWriter().close();

            }else{
                logSave(logMsg.toString(), projectName, swarmName, beeServer);
                response.getWriter().write(getRootPath(request.getRequestURI()));
                response.getWriter().close();
            }
        }
    }

    public void doEditData(final StaplerRequest request, final StaplerResponse response) throws Exception {
        String projectName = request.getParameter("projectName");
        String swarmName = request.getParameter("swarmName");
        String beeServerUrl = request.getParameter("beeServerUrl");

        for(int i=0; i<swarm_connector.getSwarmList().size(); i++){
            SwarmConnectorData tmp = swarm_connector.getSwarmList().get(i);
            if(tmp.getSwarmName().equalsIgnoreCase(swarmName) &&
                    tmp.getProjectName().equalsIgnoreCase(projectName) &&
                    tmp.getBeeRestUrl().equalsIgnoreCase(beeServerUrl)){
                this.swarmData = tmp;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("edit");
        response.getWriter().write(sb.toString());
        response.getWriter().close();
    }

    public SwarmConnectorData setDataFromParameters(JSONObject form){
        SwarmConnectorData swarm = new SwarmConnectorData();
        swarm.setProjectName(removeSpecialWord(form.getString("project_name"), "\t"));
        swarm.setSwarmName(removeSpecialWord(form.getString("swarm_name"), "\t"));
        swarm.setOwnerName(removeSpecialWord(form.getString("owner_name"), "\t"));
        swarm.setToken(removeSpecialWord(form.getString("swarm_token"), "\t"));
        swarm.setCredential(form.getString("select_credentials"));
        swarm.setLabels(form.getString("labels"));
        swarm.setBeeRestUrl(form.getString("swarm_beeRestUrl"));
        swarm.setJavaPath(removeSpecialWord(form.getString("java_path"), "\t"));
        return swarm;
    }

    public void doEditing(final StaplerRequest request, final StaplerResponse response) throws IOException {
        StringBuffer logMsg = new StringBuffer();
        boolean bError = false;
        SwarmConnectorData swarm = new SwarmConnectorData();
        try {
            String params = request.getParameter("params");
            swarm = this.setDataFromParameters(JSONObject.fromObject(JSONSerializer.toJSON(params)));
            List<SwarmConnectorData> arrSwarm = new ArrayList<SwarmConnectorData>();
            for(int i=0; i<swarm_connector.getSwarmList().size(); i++){
                SwarmConnectorData tmp = swarm_connector.getSwarmList().get(i);
                if( tmp.getSwarmName().equalsIgnoreCase(swarm.getSwarmName()) &&
                        tmp.getProjectName().equalsIgnoreCase(swarm.getProjectName()) &&
                        tmp.getBeeRestUrl().equalsIgnoreCase(swarm.getBeeRestUrl())){
                    swarm.setExecutors(swarmData.getExecutors());
                    swarm.setRemoteDirectory(swarmData.getRemoteDirectory());
                    swarm.setImageName(swarmData.getImageName());
                    swarm.setCapacity(swarmData.getCapacity());
                    swarm.setNodeNames(swarmData.getNodeNames());
                    arrSwarm.add(swarm);
                }else{
                    arrSwarm.add(tmp);
                }
            }
            logSave("[EDIT][START]", swarmData.getProjectName(), swarmData.getSwarmName(), swarmData.getBeeRestUrl());

            ArrayList<Object> resp = beeRest.requestSwarmsDetail(swarm.getBeeRestUrl(),
                    swarm.getProjectName(),
                    swarm.getSwarmName(),
                    swarm.getToken());

            if(resp.get(1).toString().equalsIgnoreCase("[SUCCESS]")){
                JSONObject jsonObject = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));
                JSONArray jsonWorkers = jsonObject.getJSONArray("workers");
                // swarm sync
                this.swarmSync(jsonWorkers, swarm);

                String log = processEditNode(jsonWorkers, swarm);
                if(log.indexOf("SUCCESS") >= 0){
                    swarm_connector.setSwarmList(arrSwarm);
                    dao.save(swarm_connector);
                }else{
                    bError = true;
                    logMsg.append(log);
                }
            }else{
                bError = true;
                logMsg.append("[FAILED] : " + resp.get(number2) + "]");
            }

        } catch (ServletException e) {
            bError = true;
            logMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        } catch (IOException e) {
            bError = true;
            logMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        } catch (Exception e) {
            bError = true;
            logMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        }finally {
            if(bError){
                logMsg.append("\n[EDIT][FAILED]");
                logSave(logMsg.toString(), swarmData.getProjectName(), swarmData.getSwarmName(), swarmData.getBeeRestUrl());
                response.getWriter().write(logMsg.toString());
                response.getWriter().close();
            }else{
                logMsg.append("[EDIT][SUCCESS]");
                logSave(logMsg.toString(), swarmData.getProjectName(), swarmData.getSwarmName(), swarmData.getBeeRestUrl());
                response.getWriter().write(getRootPath(request.getRequestURI()));
                response.getWriter().close();
            }
        }
    }

    public void doMonitor(final StaplerRequest request, final StaplerResponse response) throws IOException {
        StringBuffer logMsg = new StringBuffer();
        boolean bError = false;
        String projectName = request.getParameter("projectName");
        String swarmName = request.getParameter("swarmName");
        String beeServerUrl = request.getParameter("beeServerUrl");
        Boolean disabled = Boolean.parseBoolean(request.getParameter("disabled"));
        try {
            List<SwarmConnectorData> arrSwarm = new ArrayList<SwarmConnectorData>();
            for (int i = 0; i < swarm_connector.getSwarmList().size(); i++) {
                SwarmConnectorData swarm = swarm_connector.getSwarmList().get(i);
                if (swarm.getSwarmName().equalsIgnoreCase(swarmName) &&
                        swarm.getProjectName().equalsIgnoreCase(projectName) &&
                        swarm.getBeeRestUrl().equalsIgnoreCase(beeServerUrl)) {
                    disabled = !disabled;
                    List<String> nodeNames = swarm.getNodeNames();
                    for(int j=0; j<nodeNames.size(); j++){
                        String nodeName = nodeNames.get(j);
                        DumbSlave dumb = (DumbSlave) Jenkins.get().getNode(nodeName);

                        if(disabled) {
                            dumb.setLabelString("");
                            dumb.getComputer().setTemporarilyOffline(true, new OfflineCause.UserCause(User.current(), "disable swarm monitoring"));
                        } else {
                            dumb.setLabelString(swarm.getLabels());
                            dumb.getComputer().setTemporarilyOffline(false, new OfflineCause.UserCause(User.current(), "enable swarm monitoring"));
                        }
                        dumb.save();
                    }

                    swarm.setDisabled(disabled);

                    arrSwarm.add(swarm);
                } else {
                    arrSwarm.add(swarm);
                }
            }

            logSave("[MONITOR STATUS CHANGE][START][SWARMNAME : " + swarmName + "]", projectName, swarmName, beeServerUrl);

            swarm_connector.setSwarmList(arrSwarm);
            dao.save(swarm_connector);
            StringBuilder sb = new StringBuilder();
            sb.append("[MONITOR STATUS CHANGE][SUCCESS][SWARMNAME : " + swarmName + "]");
            response.getWriter().write(sb.toString());
            response.getWriter().close();
        } catch (Exception e) {
            bError = true;
            logMsg.append(SwarmLog.getPrintStackTrace(e));
            e.printStackTrace();
        }finally {
            if(bError){
                logMsg.append("[MONITOR STATUS CHANGE][FAILED][SWARMNAME : " + swarmName + "]");
                logSave(logMsg.toString(), projectName, swarmName, beeServerUrl);
                response.getWriter().write(logMsg.toString());
                response.getWriter().close();
            }else{
                logMsg.append("[MONITOR STATUS CHANGE][SUCCESS][SWARMNAME : " + swarmName + "]");
                logSave(logMsg.toString(), projectName, swarmName, beeServerUrl);
                response.getWriter().write(getRootPath(request.getRequestURI()));
                response.getWriter().close();
            }
        }
    }

    public void doDelete(final StaplerRequest request, final StaplerResponse response) {
        String projectName = request.getParameter("projectName");
        String swarmName = request.getParameter("swarmName");
        String beeServerUrl = request.getParameter("beeServerUrl");

        List<SwarmConnectorData> arrSwarm = new ArrayList<SwarmConnectorData>();
        SwarmConnectorData removeSwarm = null;
        if(swarm_connector == null){
            getSwarmConnector();
        }
        StringBuilder logMsg = new StringBuilder();
        try {
            for(int i=0; i<swarm_connector.getSwarmList().size(); i++){
                SwarmConnectorData tmp = swarm_connector.getSwarmList().get(i);
                if( tmp.getSwarmName().equalsIgnoreCase(swarmName) &&
                        tmp.getProjectName().equalsIgnoreCase(projectName) &&
                        tmp.getBeeRestUrl().equalsIgnoreCase(beeServerUrl)){
                    removeSwarm = tmp;
                }else{
                    arrSwarm.add(tmp);
                }
            }

            swarm_connector.setSwarmList(arrSwarm);
            dao.save(swarm_connector);
            StringBuilder sb = new StringBuilder();
            sb.append("success");
            logMsg.append("[DELETE][JENKINS BEE SWARM CONNECTOR]");
            logMsg.append("[SUCCESS]");
            removeNodeSlave(removeSwarm);
            response.getWriter().write(sb.toString());
            response.getWriter().close();
            response.sendRedirect(getRootPath(request.getRequestURI()));
        } catch (Exception e) {
            logMsg.append("[FAILED] ");
            logMsg.append(e.getMessage());
            e.printStackTrace();
        } finally {
            logSave(logMsg.toString(), removeSwarm.getProjectName(), removeSwarm.getSwarmName(), removeSwarm.getBeeRestUrl());
        }
    }


    public void doLog(final StaplerRequest request, final StaplerResponse response) throws Exception {
        String beeServer = getBeeServerName(request.getParameter("beeServerUrl"));
        Boolean weak = Boolean.valueOf(request.getParameter("weak"));
        String aWeekLog = "";
        String aDayLog = "";

        if(weak) {
            // 최근 일주일 로그 추출
            aWeekLog = logDao.getRecentOneWeekLogs(request.getParameter("projectName"),
                    request.getParameter("swarmName"),
                    beeServer);
        } else {
            // 당일 로그 추출
            aDayLog = logDao.getDaysLogs(request.getParameter("projectName"),
                    request.getParameter("swarmName"),
                    beeServer,
                    getToday());
        }

        response.getWriter().write(weak ? aWeekLog : aDayLog);
        response.getWriter().close();
    }

    public boolean reconnectNode(SwarmConnectorData swarmObj, int workerNodeNum){
        List<String> nodeNames = swarmObj.getNodeNames();

        for(int i=0; i<nodeNames.size(); i++) {
            String nodeName = nodeNames.get(i);
            int nodeNum = Integer.parseInt(nodeName.substring(nodeName.lastIndexOf('_') + 1));
            if(nodeNum == workerNodeNum) {
                String workerNodeName = nodeNames.get(i);
                DumbSlave dumb = (DumbSlave) Jenkins.get().getNode(workerNodeName);

                String logNode = "";

                if(dumb.getComputer().isOffline() && !dumb.getComputer().isConnecting()) {
                    Future<?> result = dumb.getComputer().connect(true);

                    logNode = String.format("[RECONNECT NODE] %s", workerNodeName);
                    logSave(logNode, swarmObj.getProjectName(), swarmObj.getSwarmName(), swarmObj.getBeeRestUrl());

                    try {
                        result.get();
                    } catch (InterruptedException | ExecutionException e) {
                        logNode = String.format("[RECONNECT NODE][FAIL] %s", workerNodeName);
                        logSave(logNode, swarmObj.getProjectName(), swarmObj.getSwarmName(), swarmObj.getBeeRestUrl());
                        return true;
                    }
                } else if(dumb.getComputer().isOnline() && !dumb.getComputer().isIdle()) {
                    return true;
                }
            }
        }
        return false;
    }

    public String checkLabel(List<String> jobLabels, SwarmConnectorData swarm){
        Collection<LabelAtom> list = new ArrayList<>();
        String[] labels = swarm.getLabels().split(" ");

        for(int i=0; i<labels.length; i++){
            LabelAtom labelAtom = LabelAtom.get(labels[i]);
            list.add(labelAtom);
        }

        for(int i=0; i<swarm.getNodeNames().size(); i++) {
            LabelAtom labelAtom = LabelAtom.get(swarm.getNodeNames().get(i));
            list.add(labelAtom);
        }

        for(int i=0; i<jobLabels.size(); i++){
            Label jobLabel = Label.get(jobLabels.get(i));
            if(jobLabel.matches(list)) {
                return jobLabels.toString();
            }
        }

        return "no";
    }

    public boolean checkRequestScaleOut(String jenkinsJobName, SwarmConnectorData swarmObj){

        if(!mScaleOutList.containsKey(jenkinsJobName)){
            return true;
        }else{
            int workerNodeNum = mScaleOutList.get(jenkinsJobName).workerNodeNum;

            String status = getStatusOfWorkerNode(swarmObj, workerNodeNum);

            if("Running".equalsIgnoreCase(status)) {
                return reconnectNode(swarmObj, workerNodeNum);
            }

            return false;
        }
    }

    public void removeScaleOutList(ArrayList<String> jenkinsJobList){
        ArrayList<String> removeJobList = new ArrayList<>();
        for(String scaleOutJobName : mScaleOutList.keySet()){
            boolean bRemove = false;
            for(String jobName : jenkinsJobList){
                if(scaleOutJobName.equalsIgnoreCase(jobName)){
                    bRemove = false;
                    break;
                }else{
                    bRemove = true;
                }
            }
            if(bRemove){
                removeJobList.add(scaleOutJobName);
            }
        }

        if(jenkinsJobList.size() == 0){
            mScaleOutList = new HashMap<>();
        }else{
            for(String removeJob : removeJobList){
                mScaleOutList.remove(removeJob);
            }
        }
    }

    public void addScaleOutJobName(String jenkinsJobName, ArrayList<Object> scaleOutResult){
        if(scaleOutResult.get(1).toString().indexOf("[SUCCESS]") > -1){

            JSONObject actionSwarm = JSONObject.fromObject(scaleOutResult.get(number2));

            if(actionSwarm.get("status").toString().equalsIgnoreCase("success")) {
                JSONObject result = (JSONObject) actionSwarm.get("result");

                String workerNodeName = (String) result.get("message");

                int workerNodeNum = Integer.parseInt(workerNodeName.substring(workerNodeName.lastIndexOf('-') + 1));

                mScaleOutList.put(jenkinsJobName, new ScaleOutData(workerNodeName, workerNodeNum));
            }
        }
    }

    public void processCheckLabel(List<SwarmConnectorData> swarmList, String jenkinsJobName, List<String> arrLabels){
        int swarmSize = swarmList.size();

        for(int i=0; i<swarmSize; i++){
            SwarmConnectorData swarm = swarmList.get(i);
            try {
                if(swarm.isDisabled()) {
                    continue;
                }

                String jobLabel = checkLabel(arrLabels, swarm);

                if(!jobLabel.equalsIgnoreCase("no")){
                    if(this.checkRequestScaleOut(jenkinsJobName, swarm)){
                        logSave("[DETECTED] " + jenkinsJobName + " : " + jobLabel,
                                swarm.getProjectName(),
                                swarm.getSwarmName(),
                                swarm.getBeeRestUrl());
                        this.connectStatus = checkAvailableScaleOut(swarm);
                    } else {
                        this.connectStatus = false;
                    }

                    if(this.connectStatus) {
                        ArrayList<Object> scaleOutResult = beeRest.requestScaleOut(swarm.getBeeRestUrl(),
                                swarm.getProjectName(),
                                swarm.getSwarmName(),
                                swarm.getToken());

                        addScaleOutJobName(jenkinsJobName, scaleOutResult);
                        String logMsg = String.format("[SWARM SCALE-OUT] %s", scaleOutResult.get(number2).toString());
                        logSave(logMsg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
                    }
                }
            } catch(Exception e) {
                String errorMsg = String.format("[PROCESS-CHECK-LABEL][ERROR] %s", SwarmLog.getPrintStackTrace(e));
                logSave(errorMsg, swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
            }
        }
    }

    /**
     * 함수명 : getStatusOfWorkerNode
     * 작성자 : 오에스씨코리아 김윤규
     * 내용 : 워커노드의 현재상태(Running, Preparing, Stopping, Sleeping)을 가져온다.
     *        에러가 발생할 경우 Fail을 리턴한다.
     * @param swarmObj
     * @param workerNodeNum
     * @return status of workerNode
     */
    public String getStatusOfWorkerNode(SwarmConnectorData swarm, int workerNodeNum){

        String result = "Fail";

        ArrayList<Object> resp = beeRest.requestSwarmsDetail(
                swarm.getBeeRestUrl(),
                swarm.getProjectName(),
                swarm.getSwarmName(),
                swarm.getToken()
        );

        if(resp.get(1).toString().equalsIgnoreCase("[SUCCESS]")) { // API 호출 성공

            JSONObject swarmDetailResult = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));
            JSONArray workerNodeArray = (JSONArray) swarmDetailResult.get("workers");

            for(int i=0; i<workerNodeArray.size(); i++) {
                JSONObject workerNodeObj = (JSONObject) workerNodeArray.get(i);
                String workerName = (String) workerNodeObj.get("workerName");
                int workerNum = Integer.parseInt(workerName.substring(workerName.lastIndexOf('-') + 1));
                if(workerNum == workerNodeNum) {
                    result = (String) workerNodeObj.get("status");
                }
            }
        } else {
            StringBuffer logMsg = new StringBuffer();
            logMsg.append("[getStatusOfWorkerNode FAIL: " + resp.get(number2) + "]");
            logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
        }

        return result;
    }

    public boolean checkAvailableScaleOut(SwarmConnectorData swarm){
        Boolean isAvailableScaleOut = false;

        ArrayList<Object> resp = beeRest.requestSwarmsDetail(
                swarm.getBeeRestUrl(),
                swarm.getProjectName(),
                swarm.getSwarmName(),
                swarm.getToken()
        );

        if(resp.get(1).toString().equalsIgnoreCase("[SUCCESS]")) { // API 호출 성공

            JSONObject swarmDetailResult = JSONObject.fromObject(JSONSerializer.toJSON(resp.get(number2)));
            JSONArray workerNodeArray = (JSONArray) swarmDetailResult.get("workers");

            for(int i=0; i<workerNodeArray.size(); i++) {
                JSONObject workerNodeObj = (JSONObject) workerNodeArray.get(i);
                String status = (String) workerNodeObj.get("status");

                if(status.equalsIgnoreCase("Running")) {
                    String workerName = (String) workerNodeObj.get("workerName");
                    int workerNodeNum = Integer.parseInt(workerName.substring(workerName.lastIndexOf('-') + 1));
                    reconnectNode(swarm, workerNodeNum);
                }

                if(status.equalsIgnoreCase("Sleeping")) {
                    isAvailableScaleOut = true;
                }
            }
        } else {
            isAvailableScaleOut = false;
            StringBuffer logMsg = new StringBuffer();
            logMsg.append("[FAILED] : " + resp.get(number2) + "]");
            logSave(logMsg.toString(), swarm.getProjectName(), swarm.getSwarmName(), swarm.getBeeRestUrl());
        }

        return isAvailableScaleOut;
    }

    public void runError(String errorMsg){
        if(errorMsg.length() > 0){
            List<SwarmConnectorData> swarmList = swarm_connector.getSwarmList();
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

    public void callbackFunction(){
        m_callback = new MonitorJob.Callback() {
            @Override
            public void onDetectedQueue(ArrayList<HashMap<String, ArrayList<String>>> jobList, String error) {
                if(jobList == null){
                    runError(error);
                }else{
                    ArrayList<String> jenkinsJobList = new ArrayList<>();
                    for(HashMap<String, ArrayList<String>> jobLabel : jobList){
                        for(String jobName : jobLabel.keySet()){
                            List<String> labels = jobLabel.get(jobName);
                            processCheckLabel(swarm_connector.getSwarmList(), jobName, labels);
                            jenkinsJobList.add(jobName);
                        }
                    }
                    removeScaleOutList(jenkinsJobList);
                }
            }
        };
        monitorJob.setCallBack(m_callback);
    }

    public void runCheckLabel(){

        if(m_callback == null){
            callbackFunction();
        }
        if(swarm_connector == null){
            swarm_connector = dao.getOrCreateObject();
        }

        if (detectedThread == null){
            detectedThread = new Thread(monitorJob, "check label thread");
        }
        if(!detectedThread.isAlive()){
            detectedThread.start();
        }
    }

    public String getToday() {
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd");
        return formatter.format(date);
    }
}
