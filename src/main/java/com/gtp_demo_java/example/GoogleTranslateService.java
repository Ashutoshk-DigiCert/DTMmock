package com.gtp_demo_java.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class GoogleTranslateService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleTranslateService.class);

    public static void main(String[] args) {
        logger.info("Starting Google Translate Service with {} arguments", args.length);
        MDC.put("executionId", java.util.UUID.randomUUID().toString());

        if (args.length < 1) {
            logger.error("No arguments provided");
            logger.error("Usage: java GoogleTranslateService <targetLanguage1> <targetLanguage2> ... [deleteGlossary]");
            logger.error("   or: java GoogleTranslateService <targetLanguage> updateGlossary <glossaryPath>");
            logger.error("   or: java GoogleTranslateService <targetLanguage1> <targetLanguage2> ... --previous <previousVersionFile>");
            System.exit(1);
        }

        try {
            logger.debug("Initializing service components");
            ConfigManager configManager = new ConfigManager();
            TranslationService translationService = new TranslationService(configManager);
            GlossaryManager glossaryManager = new GlossaryManager(configManager);

            if (args.length >= 3 && args[1].equals("updateGlossary")) {
                logger.info("Detected glossary update command");
                processGlossaryUpdateCommand(args, glossaryManager);
                return;
            }

            processTranslationCommand(args, configManager, translationService, glossaryManager);

        } catch (Exception e) {
            logger.error("Fatal error during execution: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            MDC.clear();
        }
    }

    private static void processTranslationCommand(String[] args, ConfigManager configManager,
                                                  TranslationService translationService, GlossaryManager glossaryManager) throws IOException {

        logger.debug("Processing translation command arguments");
        List<String> targetLanguages = new ArrayList<>();
        String previousFile = null;
        boolean shouldDeleteGlossary = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--previous") && i + 1 < args.length) {
                previousFile = args[i + 1];
                i++;
                logger.debug("Found previous version file: {}", previousFile);
            } else if (args[i].equals("deleteGlossary")) {
                shouldDeleteGlossary = true;
                logger.debug("Delete glossary flag detected");
            } else {
                targetLanguages.add(args[i]);
                logger.debug("Added target language: {}", args[i]);
            }
        }

        if (shouldDeleteGlossary) {
            deleteGlossaries(targetLanguages, glossaryManager);
            return;
        }

        processTranslations(targetLanguages, previousFile, configManager, translationService);

        if (previousFile != null) {
            updateBackupFile(previousFile, configManager);
        }
    }

    private static void deleteGlossaries(List<String> targetLanguages, GlossaryManager glossaryManager) {
        logger.info("Starting glossary deletion for {} languages", targetLanguages.size());

        for (String targetLanguage : targetLanguages) {
            MDC.put("targetLanguage", targetLanguage);
            try {
                logger.info("Deleting glossary for language: {}", targetLanguage);
                boolean deleted = glossaryManager.deleteGlossary(targetLanguage);
                if (deleted) {
                    logger.info("Successfully deleted glossary for language: {}", targetLanguage);
                } else {
                    logger.warn("Glossary for language {} does not exist, no deletion performed", targetLanguage);
                }
            } catch (IOException e) {
                logger.error("Error deleting glossary for language {}: {}", targetLanguage, e.getMessage(), e);
            } finally {
                MDC.remove("targetLanguage");
            }
        }
    }

    private static void processTranslations(List<String> targetLanguages, String previousFile,
                                            ConfigManager configManager, TranslationService translationService) throws IOException {

        logger.info("Processing translations for {} languages", targetLanguages.size());

        for (String targetLanguage : targetLanguages) {
            MDC.put("targetLanguage", targetLanguage);
            try {
                processLanguageTranslation(targetLanguage, previousFile, configManager, translationService);
            } finally {
                MDC.remove("targetLanguage");
            }
        }
    }

    private static void processLanguageTranslation(String targetLanguage, String previousFile,
                                                   ConfigManager configManager, TranslationService translationService) throws IOException {

        logger.info("Processing translation for language: {}", targetLanguage);
        String inputPropsFile = configManager.getInputFilePath();
        String outputPropsFile = configManager.getOutputFilePath(targetLanguage);

        logger.info("Reading source properties from: {}", inputPropsFile);
        List<PropertyEntry> originalEntries = FileIO.readPropertiesFile(inputPropsFile);

        if (originalEntries.isEmpty()) {
            logger.warn("No entries found in source properties file: {}. Skipping translation for language: {}",
                    inputPropsFile, targetLanguage);
            return;
        }

        logger.info("Starting translation process for {} entries", originalEntries.size());
        List<PropertyEntry> translatedEntries = translationService.translateProperties(originalEntries,
                targetLanguage, previousFile);

        Path outputPath = Paths.get(outputPropsFile);
        Files.createDirectories(outputPath.getParent());

        logger.info("Writing {} translated entries to: {}", translatedEntries.size(), outputPropsFile);
        FileIO.writePropertiesUtf8(translatedEntries, outputPath.toString());
    }

    private static void processGlossaryUpdateCommand(String[] args, GlossaryManager glossaryManager) {
        logger.info("Processing multiple glossary updates");
        String previousFile = null;
        List<String[]> glossaryUpdates = new ArrayList<>();

        // First pass: collect all parameters
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--previous")) {
                if (i + 1 < args.length) {
                    previousFile = args[i + 1];
                    i++;
                }
            } else if (i + 2 < args.length) {
                glossaryUpdates.add(new String[]{args[i], args[i + 1], args[i + 2]});
                i += 2;
            }
        }

        // Second pass: process updates
        for (String[] update : glossaryUpdates) {
            String targetLanguage = update[0];
            String command = update[1];
            String glossaryPath = update[2];

            MDC.put("targetLanguage", targetLanguage);
            try {
                if (!"updateGlossary".equals(command)) {
                    logger.error("Invalid command '{}' for language {}. Expected 'updateGlossary'",
                            command, targetLanguage);
                    continue;
                }

                Path glossaryFilePath = Paths.get(glossaryPath);
                if (!Files.exists(glossaryFilePath)) {
                    logger.warn("Glossary file not found at: {}. Skipping update for language: {}",
                            glossaryPath, targetLanguage);
                    continue;
                }

                logger.info("Processing glossary update for language: {} with file: {}",
                        targetLanguage, glossaryPath);
                glossaryManager.updateGlossary(glossaryPath, targetLanguage, previousFile);
                logger.info("Successfully completed glossary update for language: {}", targetLanguage);
            } catch (IOException e) {
                logger.error("Error updating glossary for language {}: {}", targetLanguage, e.getMessage(), e);
            } finally {
                MDC.remove("targetLanguage");
            }
        }
    }


    private static void updateBackupFile(String previousFile, ConfigManager configManager) throws IOException {
        String inputPropsFile = configManager.getInputFilePath();
        Files.copy(Paths.get(inputPropsFile), Paths.get(previousFile), StandardCopyOption.REPLACE_EXISTING);
        logger.info("Updated backup file: {}", previousFile);
    }
}