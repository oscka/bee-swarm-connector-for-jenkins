package io.jenkins.plugins.bee;

import jenkins.model.Jenkins;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class RemoveLogJob implements Job {

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {

        final String FILE_PATH = Jenkins.get().getRootDir() +File.separator+ "logs"+File.separator+"swarmLogs";
        final int DATES_OF_ONE_MONTH = 30;
        final int SECONDS_OF_DAY = 86400;
        final int MILLISECOND = 1000;
        final int NUM_TO_EXTRACT = 8; //substring 함수에서 추출하고자 하는 길이

        File rw = new File(FILE_PATH); //파일 경로에 있는 파일 가져오기
        File[] fileList = rw.listFiles(); //파일 경로에 있는 파일 리스트 배열화 하기

        List<String> dateList = new ArrayList<String>();

        String[] dateSplit;
        String onlyDate = "";
        Long dateToSeconds;
        int diffDate;

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");

        for (File file : fileList) {
            if (file.isFile()) {
                 dateSplit = file.getName().split("_");
                 
                 // 가장 마지막 인덱스가 날짜부분이 되므로 dateSplit.length - 1 로 한다.
                 // 확장자 제거를 위해 substring 함수 사용 
                 onlyDate = dateSplit[dateSplit.length - 1].substring(0, NUM_TO_EXTRACT); 

                 dateList.add(onlyDate); //날짜 리스트에 추출된 날짜 추가

                 try {
                     // 해당 날짜를 밀리초로 변환
                     Date date = new Date(dateFormat.parse(onlyDate).getTime());
                     dateToSeconds = date.getTime();

                     // 날짜 차이 계산
                     diffDate = (int) ((System.currentTimeMillis() - dateToSeconds) / (SECONDS_OF_DAY * MILLISECOND));

                     // 날짜 차이가 한달(30일) 이상일 경우 해당 로그 파일 삭제
                     if (diffDate > DATES_OF_ONE_MONTH) {
                         if(file.delete()){
                             System.out.println("30일 경과로 \n" + file.getName() + "\n파일 삭제");
                         }else{
                             System.out.println("파일 삭제 실패");
                         }
                     }
                 } catch (ParseException e) {
                     e.printStackTrace();
                 }
            }
        }
    }
}
