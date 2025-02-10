package io.jenkins.plugins.bee;

import jenkins.model.Jenkins;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class SwarmLogDao {

    public SwarmLog getOrCreateObject(String projectName, String swarmName, String beeServer) {

        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd");

        try {
            final String path = getFilePath(projectName, swarmName, beeServer, formatter.format(date));
            return (SwarmLog) Jenkins.XSTREAM.fromXML(new FileInputStream(path));
        } catch (final Exception e) {
            return new SwarmLog();
        }
    }

    /**
     * 함수명 : getRecentOneWeekLogs
     * 최근 일주일의 로그를 가져오는 함수
     * 작성자 : 오에스씨코리아 김윤규
     */
    public String getRecentOneWeekLogs(String projectName, String swarmName, String beeServer) throws FileNotFoundException {

        final int ONE_WEEK_DAYS = 7;

        List<String> oneWeekDatesArr = new ArrayList<String>();

        StringBuilder aWeekLogs = new StringBuilder();

        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        
        // 최근 일주일의 날짜를 배열로 저장
        for(int i=0; i<ONE_WEEK_DAYS; i++){
            if(i != 0) {
                cal.add(Calendar.DATE, -1);
            }
            //날짜를 오래된 날짜가 배열의 앞쪽으로 오도록 한다.
            oneWeekDatesArr.add(0, dateFormat.format(cal.getTime()));
        }


        SwarmLog log = new SwarmLog();

        for (int k=0;  k<oneWeekDatesArr.size(); k++) {

            String filePath = getFilePath(projectName, swarmName, beeServer, oneWeekDatesArr.get(k));
            File file = new File(filePath);

            if(file.exists()){
                log = (SwarmLog) Jenkins.XSTREAM.fromXML(new FileInputStream(filePath));
                aWeekLogs.append(log.getLogs());
            }
        }

        return aWeekLogs.toString();
    }

    /**
     * 함수명 : getRecentOneWeekLogs
     * 요청한 날짜의 로그를 가져오는 함수
     */
    public String getDaysLogs(String projectName, String swarmName, String beeServer, String formatDate) throws FileNotFoundException {

        StringBuilder dayLogs = new StringBuilder();

        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());

        SwarmLog log = new SwarmLog();

        String filePath = getFilePath(projectName, swarmName, beeServer, formatDate);
        File file = new File(filePath);

        if(file.exists()){
            log = (SwarmLog) Jenkins.XSTREAM.fromXML(new FileInputStream(filePath));
            dayLogs.append(log.getLogs());
        }

        return dayLogs.toString();
    }

    public void save(SwarmLog object, String projectName, String swarmName, String beeServer){
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd");
        FileOutputStream outputStream = null;
        OutputStreamWriter outputStreamWriter = null;
        try {
            outputStream = new FileOutputStream(getFilePath(projectName, swarmName, beeServer, formatter.format(date)));
            outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
            Jenkins.XSTREAM.toXML(object, outputStreamWriter);
        } catch (final Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if(outputStream != null) {
                    outputStream.close();
                }
                if(outputStreamWriter != null) {
                    outputStreamWriter.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 함수명 : getFilePath
     * @param projectName: 프로젝트 이름
     * @param swarmName: 스웜 이름
     * @param beeServer: 서버 이름
     * @param formatDate: 날짜(형식 - yyyyMMdd)
     * @return
     */
    private static String getFilePath(String projectName, String swarmName, String beeServer, String formatDate) {
        // 로그가 저장되는 경로 상수 지정
        final String logPath = Jenkins.get().getRootDir() +File.separator+ "logs"+File.separator+"swarmLogs";

        String logFileName = projectName+"-"+swarmName+"-"+beeServer+"_log"+"_"+formatDate+".xml";

        return logPath + File.separator + logFileName;
    }
}
