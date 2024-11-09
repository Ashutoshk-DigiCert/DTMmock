package com.gtp_demo_java.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private final Properties config;

    public ConfigManager() throws IOException {
        logger.info("Initializing ConfigManager");
        this.config = loadConfig();
    }

    public Properties getConfig() {
        logger.trace("Retrieving configuration properties");
        return config;
    }

    private Properties loadConfig() throws IOException {
        logger.info("Loading configuration from application.properties");
        Properties properties = new Properties();

        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                logger.error("Failed to find application.properties file");
                throw new IOException("Unable to find application.properties");
            }

            properties.load(input);
            logger.info("Successfully loaded {} configuration properties", properties.size());

            if (logger.isDebugEnabled()) {
                properties.stringPropertyNames().forEach(key ->
                        logger.debug("Loaded property: {} = {}", key,
                                key.toLowerCase().contains("secret") ? "****" : properties.getProperty(key))
                );
            }

            return properties;
        } catch (IOException e) {
            logger.error("Error loading configuration: {}", e.getMessage(), e);
            throw e;
        }
    }

    public String getProjectId() {
        String projectId = config.getProperty("google.project.id");
        logger.debug("Retrieved project ID: {}", projectId);
        return projectId;
    }

    public String getLocation() {
        String location = config.getProperty("google.location");
        logger.debug("Retrieved location: {}", location);
        return location;
    }

    public String getBucketName() {
        String bucketName = config.getProperty("google.bucket.name");
        logger.debug("Retrieved bucket name: {}", bucketName);
        return bucketName;
    }

    public String getGlossaryFileName(String targetLanguage) {
        String fileName = String.format(config.getProperty("glossary.file.format"), targetLanguage.toLowerCase());
        logger.debug("Generated glossary filename for language {}: {}", targetLanguage, fileName);
        return fileName;
    }

    public String getInputFilePath() {
        String inputPath = config.getProperty("file.input.path");
        logger.debug("Retrieved input file path: {}", inputPath);
        return inputPath;
    }

    public String getOutputFilePath(String targetLanguage) {
        String outputPath = String.format(config.getProperty("file.output.path.format"), targetLanguage);
        logger.debug("Generated output path for language {}: {}", targetLanguage, outputPath);
        return outputPath;
    }
}