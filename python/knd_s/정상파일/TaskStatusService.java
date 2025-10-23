package com.mybaselink.app.service;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskStatusService {

    private final Map<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();

    public static class TaskStatus {
        private String status; // IN_PROGRESS, COMPLETED, FAILED
        private Object result;
        private String errorMessage;

        public TaskStatus(String status, Object result, String errorMessage) {
            this.status = status;
            this.result = result;
            this.errorMessage = errorMessage;
        }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public void setTaskStatus(String taskId, TaskStatus status) {
        taskStatusMap.put(taskId, status);
    }

    public TaskStatus getTaskStatus(String taskId) {
        return taskStatusMap.get(taskId);
    }
}
