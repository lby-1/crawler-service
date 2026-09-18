package com.jpwise.crawler.model;

import lombok.Data;

import java.util.Date;

@Data
public class JobStatus {
    private String jobId;
    /** pending / running / done / failed */
    private String status;
    private int progress;
    private String message;
    private Date startTime;
    private Date endTime;

    public static JobStatus running(String jobId, String message) {
        JobStatus s = new JobStatus();
        s.setJobId(jobId);
        s.setStatus("running");
        s.setMessage(message);
        s.setStartTime(new Date());
        return s;
    }

    public static JobStatus done(String jobId, String message) {
        JobStatus s = new JobStatus();
        s.setJobId(jobId);
        s.setStatus("done");
        s.setProgress(100);
        s.setMessage(message);
        s.setEndTime(new Date());
        return s;
    }

    public static JobStatus failed(String jobId, String message) {
        JobStatus s = new JobStatus();
        s.setJobId(jobId);
        s.setStatus("failed");
        s.setMessage(message);
        s.setEndTime(new Date());
        return s;
    }
}
