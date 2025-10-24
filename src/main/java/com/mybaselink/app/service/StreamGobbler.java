package com.mybaselink.app.service;

import org.slf4j.Logger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class StreamGobbler implements Runnable {
    private final InputStream inputStream;
    private final Logger logger;
    private final String taskId;
    private final StringBuilder output = new StringBuilder();

    public StreamGobbler(InputStream inputStream, Logger logger, String taskId) {
        this.inputStream = inputStream;
        this.logger = logger;
        this.taskId = taskId;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[{}] [PYTHON] {}", taskId, line);
                output.append(line).append("\n");
            }
        } catch (IOException e) {
            logger.error("[{}] 스트림 읽기 중 오류 발생", taskId, e);
        }
    }

    public StringBuilder getOutput() {
        return output;
    }
}
