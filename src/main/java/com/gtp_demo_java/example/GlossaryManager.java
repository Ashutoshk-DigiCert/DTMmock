package com.gtp_demo_java.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.storage.*;
import com.google.cloud.translate.v3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GlossaryManager {
    private static final Logger logger = LoggerFactory.getLogger(GlossaryManager.class);
    private final ConfigManager configManager;

    public GlossaryManager(ConfigManager configManager) {
        logger.info("Initializing GlossaryManager");
        this.configManager = configManager;
    }

    public boolean deleteGlossary(String targetLanguage) throws IOException {
        logger.info("Initiating glossary deletion for language: {}", targetLanguage);
        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(configManager.getProjectId(), configManager.getLocation())
                    .toString() + "/glossaries/" + glossaryId;

            logger.debug("Attempting to delete glossary: {}", glossaryName);

            try {
                OperationFuture<DeleteGlossaryResponse, DeleteGlossaryMetadata> future =
                        client.deleteGlossaryAsync(glossaryName);

                logger.debug("Waiting for glossary deletion operation to complete");
                DeleteGlossaryResponse response = future.get(5, TimeUnit.MINUTES);
                logger.info("Successfully deleted glossary: {}", response.getName());
                return true;

            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                return handleDeletionException(e, glossaryName);
            }
        } catch (Exception e) {
            logger.error("Failed to create TranslationServiceClient: {}", e.getMessage(), e);
            throw new IOException("Error creating TranslationServiceClient", e);
        }
    }

    private boolean handleDeletionException(Exception e, String glossaryName) throws IOException {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            logger.error("Glossary deletion interrupted: {}", glossaryName);
            throw new IOException("Glossary deletion interrupted", e);
        } else if (e instanceof ExecutionException && e.getCause() instanceof ApiException) {
            ApiException apiException = (ApiException) e.getCause();
            if (apiException.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                logger.info("Glossary not found, no deletion required: {}", glossaryName);
                return false; // Glossary does not exist
            } else {
                logger.error("API error during glossary deletion: {} - {}",
                        apiException.getStatusCode().getCode(), apiException.getMessage());
                throw new IOException("API error during glossary deletion", apiException);
            }
        } else if (e instanceof TimeoutException) {
            logger.error("Glossary deletion timed out: {}", glossaryName);
            throw new IOException("Glossary deletion timed out", e);
        } else {
            logger.error("Unexpected error during glossary deletion: {}", e.getMessage(), e);
            throw new IOException("Unexpected error during glossary deletion", e);
        }
    }

    public void uploadGlossaryToCloudStorage(String filePath, String targetLanguage) throws IOException {
        logger.info("Starting glossary file upload to Cloud Storage for language: {}", targetLanguage);

        try {
            Storage storage = StorageOptions.newBuilder()
                    .setProjectId(configManager.getProjectId())
                    .build()
                    .getService();

            String glossaryFileName = configManager.getGlossaryFileName(targetLanguage);
            logger.debug("Preparing to upload file: {} as {}", filePath, glossaryFileName);

            BlobId blobId = BlobId.of(configManager.getBucketName(), glossaryFileName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();

            Path path = Paths.get(filePath);
            byte[] content = Files.readAllBytes(path);
            logger.debug("Read {} bytes from glossary file", content.length);

            Blob blob = storage.create(blobInfo, content);
            logger.debug("Upload completed, verifying blob existence");

            if (blob == null || !blob.exists()) {
                logger.error("Failed to verify uploaded file in Cloud Storage");
                throw new IOException("Failed to verify uploaded file in Cloud Storage");
            }

            logger.info("Successfully uploaded glossary file {} ({} bytes) to bucket {}",
                    glossaryFileName, content.length, configManager.getBucketName());

        } catch (StorageException e) {
            logger.error("Storage error during glossary upload: {}", e.getMessage(), e);
            throw new IOException("Failed to upload glossary file to Cloud Storage", e);
        }
    }

    public void updateGlossary(String glossaryFilePath, String targetLanguage) throws IOException {
        logger.info("Starting glossary update process for language: {}", targetLanguage);
        MDC.put("targetLanguage", targetLanguage);

        try {
            Path glossaryPath = Paths.get(glossaryFilePath);
            if (!Files.exists(glossaryPath)) {
                logger.info("Glossary file not found at: {}. Skipping update.", glossaryFilePath);
                return;
            }

            logger.info("Step 1/3: Uploading glossary file to Cloud Storage");
            uploadGlossaryToCloudStorage(glossaryFilePath, targetLanguage);

            logger.info("Step 2/3: Removing existing glossary if present");
            try {
                deleteGlossary(targetLanguage);
            } catch (IOException e) {
                if (e.getMessage().contains("NOT_FOUND")) {
                    logger.info("No existing glossary found to delete");
                } else {
                    throw e;
                }
            }

            logger.info("Step 3/3: Creating new glossary");
            try (TranslationServiceClient client = TranslationServiceClient.create()) {
                LocationName parent = LocationName.of(configManager.getProjectId(), configManager.getLocation());
                createGlossary(client, parent, targetLanguage);
            }

            logger.info("Glossary update completed successfully for language: {}", targetLanguage);
        } catch (Exception e) {
            logger.error("Failed to process glossary update: {}", e.getMessage(), e);
            throw new IOException("Glossary update failed", e);
        } finally {
            MDC.remove("targetLanguage");
        }
    }

    private void createGlossary(TranslationServiceClient client, LocationName parent,
                                String targetLanguage) throws IOException {
        logger.info("=== Starting Glossary Creation for {} ===", targetLanguage);

        String inputUri = String.format("gs://%s/%s", configManager.getBucketName(),
                configManager.getGlossaryFileName(targetLanguage));
        logger.info("📂 Using Cloud Storage URI: {}", inputUri);

        try {
            GcsSource gcsSource = GcsSource.newBuilder().setInputUri(inputUri).build();
            GlossaryInputConfig inputConfig = GlossaryInputConfig.newBuilder()
                    .setGcsSource(gcsSource)
                    .build();

            Glossary.LanguageCodePair languageCodePair = Glossary.LanguageCodePair.newBuilder()
                    .setSourceLanguageCode("en")
                    .setTargetLanguageCode(targetLanguage)
                    .build();
            logger.info("🔄 Configured language pair: en -> {}", targetLanguage);

            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String fullGlossaryName = LocationName.of(configManager.getProjectId(),
                    configManager.getLocation()).toString() + "/glossaries/" + glossaryId;

            logger.info("🔨 Creating glossary with ID: {}", glossaryId);

            Glossary glossary = Glossary.newBuilder()
                    .setName(fullGlossaryName)
                    .setLanguagePair(languageCodePair)
                    .setInputConfig(inputConfig)
                    .build();

            CreateGlossaryRequest request = CreateGlossaryRequest.newBuilder()
                    .setParent(parent.toString())
                    .setGlossary(glossary)
                    .build();

            logger.info("⏳ Submitting glossary creation request...");

            OperationFuture<Glossary, CreateGlossaryMetadata> future = client.createGlossaryAsync(request);

            int timeoutMinutes = Integer.parseInt(
                    configManager.getConfig().getProperty("glossary.creation.timeout.minutes", "5"));
            logger.info("⏱️ Waiting up to {} minutes for glossary creation", timeoutMinutes);

            Glossary createdGlossary = future.get(timeoutMinutes, TimeUnit.MINUTES);
            logger.info("✅ Glossary created successfully: {}", createdGlossary.getName());
            logger.info("=== Glossary Creation Completed for {} ===", targetLanguage);

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            handleGlossaryCreationException(e, targetLanguage);
        }
    }

    private void handleGlossaryCreationException(Exception e, String targetLanguage) throws IOException {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            logger.error("Glossary creation interrupted for language: {}", targetLanguage);
            throw new IOException("Glossary creation interrupted", e);
        } else if (e instanceof ExecutionException) {
            handleExecutionException((ExecutionException) e);
        } else if (e instanceof TimeoutException) {
            logger.error("Glossary creation timed out for language: {}", targetLanguage);
            throw new IOException("Glossary creation timed out", e);
        }
    }

    private void handleExecutionException(ExecutionException e) throws IOException {
        if (e.getCause() instanceof ApiException) {
            handleApiException((ApiException) e.getCause());
        } else {
            logger.error("Unexpected error during glossary creation: {}", e.getMessage(), e);
            throw new IOException("Unexpected error during glossary creation", e);
        }
    }

    private void handleApiException(ApiException e) throws IOException {
        StatusCode.Code code = e.getStatusCode().getCode();
        String message = String.format("API error during glossary creation (Code: %s): %s",
                code, e.getMessage());

        switch (code) {
            case ALREADY_EXISTS:
                logger.info("Glossary already exists, proceeding with existing glossary");
                break;
            case INVALID_ARGUMENT:
                logger.error("Invalid glossary configuration: {}", e.getMessage());
                throw new IOException("Invalid glossary configuration: " + e.getMessage(), e);
            case PERMISSION_DENIED:
                logger.error("Permission denied during glossary creation");
                throw new IOException("Permission denied. Check credentials and permissions", e);
            case NOT_FOUND:
                logger.error("Resource not found in Cloud Storage");
                throw new IOException("Resource not found in Cloud Storage", e);
            case RESOURCE_EXHAUSTED:
                logger.error("Resource quota exceeded during glossary creation");
                throw new IOException("Resource quota exceeded", e);
            default:
                logger.error(message);
                throw new IOException(message, e);
        }
    }
}