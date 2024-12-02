package com.gtp_demo_java.example;

import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextGlossaryConfig;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.TranslationServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TranslationService {
    private static final Logger logger = LoggerFactory.getLogger(TranslationService.class);
    private final ConfigManager configManager;

    public TranslationService(ConfigManager configManager) {
        logger.info("Initializing TranslationService");
        this.configManager = configManager;
    }

    public List<PropertyEntry> translateProperties(List<PropertyEntry> entries, String targetLanguage, String previousVersionFile) throws IOException {
        MDC.put("targetLanguage", targetLanguage);
        logger.info("Starting translation process for {} entries to {}", entries.size(), targetLanguage);

        String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (credentialsPath == null || credentialsPath.isEmpty()) {
            logger.error("GOOGLE_APPLICATION_CREDENTIALS environment variable not set");
            throw new IOException("GOOGLE_APPLICATION_CREDENTIALS environment variable not set");
        }
        logger.debug("Using credentials from: {}", credentialsPath);

        List<PropertyEntry> translatedEntries = new ArrayList<>();
        Map<String, PropertyEntry> existingTranslationsMap = new HashMap<>();

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(configManager.getProjectId(), configManager.getLocation());
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(configManager.getProjectId(), configManager.getLocation()).toString()
                    + "/glossaries/" + glossaryId;

            boolean glossaryExists = checkGlossaryExists(client, glossaryName);
            logger.info("Glossary status for {}: {}", targetLanguage, glossaryExists ? "found" : "not found");

            if (!glossaryExists) {
                logger.info("Glossary not found. Creating new glossary for language: {}", targetLanguage);
                try {
                    GlossaryManager glossaryManager = new GlossaryManager(configManager);
                    glossaryExists = createGlossaryForTranslation(glossaryManager, client, parent, targetLanguage);
                } catch (Exception e) {
                    logger.warn("Failed to create glossary for language {}: {}. Proceeding with translation without glossary.",
                            targetLanguage, e.getMessage());
                }
            }

            List<PropertyEntry> modifiedEntries = entries;

            if (previousVersionFile != null) {
                logger.info("Processing incremental translation using previous version file: {}", previousVersionFile);
                String currentFile = configManager.getInputFilePath();
                modifiedEntries = getModifiedEntries(previousVersionFile, currentFile);
                logger.info("Identified {} modified/new entries for translation", modifiedEntries.size());

                loadExistingTranslations(targetLanguage, existingTranslationsMap);
            }

            processEntries(entries, modifiedEntries, translatedEntries, existingTranslationsMap,
                    client, parent, targetLanguage, glossaryExists, glossaryName);

        } catch (Exception e) {
            logger.error("Translation process failed: {}", e.getMessage(), e);
            throw new IOException("Translation process failed", e);
        } finally {
            MDC.remove("targetLanguage");
        }

        return translatedEntries;
    }

    private boolean createGlossaryForTranslation(GlossaryManager glossaryManager, TranslationServiceClient client,
                                                 LocationName parent, String targetLanguage) throws IOException {
        try {
            logger.info("Attempting to create glossary for language: {}", targetLanguage);
            glossaryManager.createGlossary(client, parent, targetLanguage);
            logger.info("Successfully created glossary for language: {}", targetLanguage);
            return true;
        } catch (Exception e) {
            logger.error("Failed to create glossary for language {}: {}", targetLanguage, e.getMessage());
            return false;
        }
    }

    private boolean checkGlossaryExists(TranslationServiceClient client, String glossaryName) {
        try {
            logger.info("Checking existence of glossary: {}", glossaryName);
            client.getGlossary(glossaryName);
            logger.info("Glossary found: {}", glossaryName);
            return true;
        } catch (Exception e) {
            logger.info("Glossary not found: {}", glossaryName);
            return false;
        }
    }

    private void loadExistingTranslations(String targetLanguage, Map<String, PropertyEntry> existingTranslationsMap) {
        String existingTranslationsFile = configManager.getOutputFilePath(targetLanguage);
        try {
            List<PropertyEntry> existingTranslations = FileIO.readPropertiesFile(existingTranslationsFile);
            logger.info("Loaded {} existing translations from {}", existingTranslations.size(), existingTranslationsFile);

            for (PropertyEntry entry : existingTranslations) {
                if (entry.type == PropertyEntry.EntryType.PROPERTY) {
                    existingTranslationsMap.put(entry.key, entry);
                }
            }
        } catch (IOException e) {
            logger.warn("No existing translations found at: {}. Will create new file.", existingTranslationsFile);
        }
    }

    private void processEntries(List<PropertyEntry> entries, List<PropertyEntry> modifiedEntries,
                                List<PropertyEntry> translatedEntries, Map<String, PropertyEntry> existingTranslationsMap,
                                TranslationServiceClient client, LocationName parent, String targetLanguage,
                                boolean glossaryExists, String glossaryName) {

        int totalEntries = entries.size();
        int processedCount = 0;

        for (PropertyEntry entry : entries) {
            processedCount++;
            if (entry.type != PropertyEntry.EntryType.PROPERTY) {
                logger.trace("Copying non-property entry type: {}", entry.type);
                translatedEntries.add(entry);
                continue;
            }

            try {
                processPropertyEntry(entry, modifiedEntries, translatedEntries, existingTranslationsMap,
                        client, parent, targetLanguage, glossaryExists, glossaryName);

                if (processedCount % 100 == 0) {
                    logger.debug("Translation progress: {}/{} entries processed", processedCount, totalEntries);
                }

            } catch (Exception e) {
                logger.error("Failed to translate property: {}", entry.key, e);
                translatedEntries.add(entry);
            }
        }
    }


    private void translateAndAddEntry(PropertyEntry entry, List<PropertyEntry> translatedEntries,
                                      TranslationServiceClient client, LocationName parent,
                                      String targetLanguage, boolean glossaryExists, String glossaryName) {
        try {
            String fullValue = String.join("\n", entry.lines);
            String translatedValue = glossaryExists ?
                    translateValueWithGlossary(client, parent, fullValue, targetLanguage, glossaryName) :
                    translateValueWithoutGlossary(client, parent, fullValue, targetLanguage);

            translatedEntries.add(new PropertyEntry(entry.key,
                    List.of(translatedValue.split("\n")),
                    PropertyEntry.EntryType.PROPERTY));

            logger.debug("Successfully translated entry: {}", entry.key);
        } catch (Exception e) {
            logger.error("Translation failed for entry: {}. Error: {}", entry.key, e.getMessage(), e);
            translatedEntries.add(entry); // Fallback to original entry
        }
    }


    private String translateValueWithGlossary(TranslationServiceClient client, LocationName parent,
                                              String value, String targetLanguage, String glossaryName) {
        logger.debug("Attempting translation with glossary - Length: {}, Target: {}", value.length(), targetLanguage);

        try {
            int separatorIndex = value.indexOf('=');
            String key = value.substring(0, separatorIndex + 1);
            String contentToTranslate = value.substring(separatorIndex + 1);

            String cleanContent = cleanContentForTranslation(contentToTranslate);
            logger.trace("Cleaned content for translation: {}", cleanContent);

            // Normalize the content for glossary lookup
            String normalizedContent = cleanContent.toLowerCase();

            TranslateTextGlossaryConfig glossaryConfig = TranslateTextGlossaryConfig.newBuilder()
                    .setGlossary(glossaryName)
                    .build();

            TranslateTextRequest request = buildTranslationRequest(parent, targetLanguage, normalizedContent, glossaryConfig);

            TranslateTextResponse response = client.translateText(request);
            String translatedText = response.getGlossaryTranslations(0).getTranslatedText().trim();
            logger.debug("Successfully translated text with glossary");
            return formatTranslatedText(key, contentToTranslate, translatedText);

        } catch (Exception e) {
            logger.info("Proceeding with standard translation for {} as glossary is not available", targetLanguage);
            return translateValueWithoutGlossary(client, parent, value, targetLanguage);
        }
    }

    private final Map<String, Boolean> glossaryAvailabilityLogged = new HashMap<>();

    private void processPropertyEntry(PropertyEntry entry, List<PropertyEntry> modifiedEntries,
                                      List<PropertyEntry> translatedEntries, Map<String, PropertyEntry> existingTranslationsMap,
                                      TranslationServiceClient client, LocationName parent, String targetLanguage,
                                      boolean glossaryExists, String glossaryName) {

        MDC.put("propertyKey", entry.key);
        logger.debug("Processing property entry: {}", entry.key);

        try {
            boolean isModified = modifiedEntries.stream()
                    .anyMatch(e -> e.key.equals(entry.key));

            if (existingTranslationsMap.isEmpty()) {
                if (glossaryExists) {
                    translateAndAddEntry(entry, translatedEntries, client, parent, targetLanguage, true, glossaryName);
                } else {
                    // Log only once per language
                    if (!glossaryAvailabilityLogged.containsKey(targetLanguage)) {
                        logger.info("No glossary available for {}, proceeding with standard translation", targetLanguage);
                        glossaryAvailabilityLogged.put(targetLanguage, true);
                    }
                    translateAndAddEntry(entry, translatedEntries, client, parent, targetLanguage, false, null);
                }
            } else if (isModified || !existingTranslationsMap.containsKey(entry.key)) {
                logger.info("Translating modified/new entry: {}", entry.key);
                if (glossaryExists) {
                    translateAndAddEntry(entry, translatedEntries, client, parent, targetLanguage, true, glossaryName);
                } else {
                    // Log only once per language
                    if (!glossaryAvailabilityLogged.containsKey(targetLanguage)) {
                        logger.info("No glossary available for {}, proceeding with standard translation", targetLanguage);
                        glossaryAvailabilityLogged.put(targetLanguage, true);
                    }
                    translateAndAddEntry(entry, translatedEntries, client, parent, targetLanguage, false, null);
                }
            } else {
                logger.debug("Using existing translation for: {}", entry.key);
                translatedEntries.add(existingTranslationsMap.get(entry.key));
            }
        } catch (Exception e) {
            logger.error("Error processing entry {}: {}. Using standard translation as fallback.",
                    entry.key, e.getMessage());
            translateAndAddEntry(entry, translatedEntries, client, parent, targetLanguage, false, null);
        } finally {
            MDC.remove("propertyKey");
        }
    }



    private String translateValueWithoutGlossary(TranslationServiceClient client, LocationName parent,
                                                 String value, String targetLanguage) {
        logger.debug("Translating without glossary - Length: {}, Target: {}", value.length(), targetLanguage);

        int separatorIndex = value.indexOf('=');
        String key = value.substring(0, separatorIndex + 1);
        String contentToTranslate = value.substring(separatorIndex + 1);

        String cleanContent = cleanContentForTranslation(contentToTranslate);
        logger.trace("Cleaned content for translation: {}", cleanContent);

        TranslateTextRequest request = buildTranslationRequest(parent, targetLanguage, cleanContent, null);

        try {
            TranslateTextResponse response = client.translateText(request);
            String translatedText = response.getTranslations(0).getTranslatedText().trim();
            logger.debug("Successfully translated text without glossary");
            return formatTranslatedText(key, contentToTranslate, translatedText);
        } catch (Exception e) {
            logger.error("Translation without glossary failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    private TranslateTextRequest buildTranslationRequest(LocationName parent, String targetLanguage,
                                                         String content, TranslateTextGlossaryConfig glossaryConfig) {
        TranslateTextRequest.Builder builder = TranslateTextRequest.newBuilder()
                .setParent(parent.toString())
                .setMimeType("text/plain")
                .setSourceLanguageCode("en")
                .setTargetLanguageCode(targetLanguage)
                .addContents(content);

        if (glossaryConfig != null) {
            builder.setGlossaryConfig(glossaryConfig);
        }

        return builder.build();
    }

    private String cleanContentForTranslation(String content) {
        return content.replaceAll("\\{\\d+\\}", "PLACEHOLDER")
                .replaceAll("\\$\\{.*?\\}", "VARIABLE")
                .trim();
    }

//    private String formatTranslatedText(String key, String originalContent, String translatedText) {
//        String formattedText = translatedText;
//
//        if (originalContent.contains("{") && originalContent.contains("}")) {
//            formattedText = restorePlaceholders(originalContent, formattedText);
//        }
//
//        if (originalContent.contains("${")) {
//            formattedText = restoreVariables(originalContent, formattedText);
//        }
//
//        return key + formattedText;
//    }

    // In the TranslationService class, modify the formatTranslatedText method

    private String formatTranslatedText(String key, String originalContent, String translatedText) {
        String formattedText = translatedText;

        if (originalContent.contains("{") && originalContent.contains("}")) {
            formattedText = restorePlaceholders(originalContent, formattedText);
        }

        if (originalContent.contains("${")) {
            formattedText = restoreVariables(originalContent, formattedText);
        }

        // Remove any existing line continuations and join multiple lines
        formattedText = formattedText.replaceAll("\\\\\n\\s*", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return key + formattedText;
    }


    private String restorePlaceholders(String original, String translated) {
        String[] translatedParts = translated.split("PLACEHOLDER");

        StringBuilder result = new StringBuilder();
        int placeholderIndex = 0;

        for (int i = 0; i < translatedParts.length; i++) {
            result.append(translatedParts[i].trim());
            if (i < translatedParts.length - 1) {
                String placeholder = findPlaceholder(original, placeholderIndex++);
                result.append(placeholder);
            }
        }

        return result.toString();
    }

    private String restoreVariables(String original, String translated) {
        String[] translatedParts = translated.split("VARIABLE");

        StringBuilder result = new StringBuilder();
        int variableIndex = 0;

        for (int i = 0; i < translatedParts.length; i++) {
            result.append(translatedParts[i].trim());
            if (i < translatedParts.length - 1) {
                String variable = findVariable(original, variableIndex++);
                result.append(variable);
            }
        }

        return result.toString();
    }

    private String findPlaceholder(String text, int index) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\d+\\}");
        java.util.regex.Matcher matcher = pattern.matcher(text);

        for (int i = 0; i <= index; i++) {
            if (!matcher.find()) return "";
        }

        return matcher.group();
    }

    private String findVariable(String text, int index) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{.*?\\}");
        java.util.regex.Matcher matcher = pattern.matcher(text);

        for (int i = 0; i <= index; i++) {
            if (!matcher.find()) return "";
        }

        return matcher.group();
    }

    private List<PropertyEntry> getModifiedEntries(String previousFile, String currentFile) throws IOException {
        List<PropertyEntry> previousEntries = FileIO.readPropertiesFile(previousFile);
        List<PropertyEntry> currentEntries = FileIO.readPropertiesFile(currentFile);

        Map<String, String> previousMap = new HashMap<>();
        for (PropertyEntry entry : previousEntries) {
            if (entry.type == PropertyEntry.EntryType.PROPERTY) {
                previousMap.put(entry.key, String.join("\n", entry.lines));
            }
        }

        List<PropertyEntry> modifiedEntries = new ArrayList<>();
        for (PropertyEntry entry : currentEntries) {
            if (entry.type == PropertyEntry.EntryType.PROPERTY) {
                String currentValue = String.join("\n", entry.lines);
                String previousValue = previousMap.get(entry.key);

                if (previousValue == null || !previousValue.equals(currentValue)) {
                    modifiedEntries.add(entry);
                }
            }
        }
        return modifiedEntries;
    }
}