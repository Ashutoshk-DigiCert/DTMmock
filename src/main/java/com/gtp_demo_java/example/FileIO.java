package com.gtp_demo_java.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FileIO {
    private static final Logger logger = LoggerFactory.getLogger(FileIO.class);

    public static List<PropertyEntry> readPropertiesFile(String inputFile) throws IOException {
        logger.info("Starting to read properties file: {}", inputFile);
        List<PropertyEntry> entries = new ArrayList<>();
        Path inputPath = Paths.get(inputFile);

        if (!Files.exists(inputPath)) {
            logger.warn("Source properties file does not exist at path: {}", inputFile);
            return entries;
        }

        logger.debug("File exists, beginning to read contents");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8))) {

            String line;
            List<String> currentLines = new ArrayList<>();
            String currentKey = null;
            PropertyEntry.EntryType currentType = null;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                logger.trace("Processing line {}: {}", lineNumber, line);

                if (line.trim().startsWith("#")) {
                    logger.trace("Found comment at line {}", lineNumber);
                    if (currentType != null) {
                        entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                        logger.debug("Added comment entry with key: {}", currentKey);
                        currentLines.clear();
                    }
                    currentKey = line;
                    currentLines.add(line);
                    currentType = PropertyEntry.EntryType.COMMENT;

                } else if (line.trim().isEmpty()) {
                    logger.trace("Found empty line at line {}", lineNumber);
                    if (currentType != null) {
                        entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                        logger.debug("Added empty line entry");
                        currentLines.clear();
                    }
                    currentKey = "";
                    currentLines.add(line);
                    currentType = PropertyEntry.EntryType.EMPTY_LINE;

                } else {
                    int separatorIndex = line.indexOf('=');
                    if (separatorIndex > 0 && (currentType != PropertyEntry.EntryType.PROPERTY ||
                            !currentLines.get(currentLines.size() - 1).trim().endsWith("\\"))) {

                        logger.trace("Found property at line {}", lineNumber);
                        if (currentType != null) {
                            entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                            logger.debug("Added property entry with key: {}", currentKey);
                            currentLines.clear();
                        }
                        currentKey = line.substring(0, separatorIndex).trim();
                        currentLines.add(line);
                        currentType = PropertyEntry.EntryType.PROPERTY;

                    } else {
                        logger.trace("Adding continuation line {} to current property", lineNumber);
                        currentLines.add(line);
                    }
                }
            }

            if (currentType != null) {
                entries.add(new PropertyEntry(currentKey, currentLines, currentType));
                logger.debug("Added final entry with key: {}", currentKey);
            }

            logger.info("Successfully read {} entries from properties file", entries.size());
            if (logger.isDebugEnabled()) {
                logger.debug("Entry type breakdown - Properties: {}, Comments: {}, Empty Lines: {}",
                        entries.stream().filter(e -> e.type == PropertyEntry.EntryType.PROPERTY).count(),
                        entries.stream().filter(e -> e.type == PropertyEntry.EntryType.COMMENT).count(),
                        entries.stream().filter(e -> e.type == PropertyEntry.EntryType.EMPTY_LINE).count());
            }

            return entries;

        } catch (IOException e) {
            logger.error("Failed to read properties file: {}. Error: {}", inputFile, e.getMessage(), e);
            throw new IOException("Error reading properties file: " + inputFile, e);
        }
    }

    public static void writePropertiesUtf8(List<PropertyEntry> entries, String filename) throws IOException {
        logger.info("Starting to write {} entries to file: {}", entries.size(), filename);

        Path filePath = Paths.get(filename);
        if (!Files.exists(filePath.getParent())) {
            logger.debug("Creating directory structure for: {}", filePath.getParent());
            Files.createDirectories(filePath.getParent());
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(filename), StandardCharsets.UTF_8))) {

            int entryCount = 0;
            for (PropertyEntry entry : entries) {
                entryCount++;
                logger.trace("Writing entry {}/{} of type: {}", entryCount, entries.size(), entry.type);

                for (String line : entry.lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }

            logger.info("Successfully wrote {} entries to file: {}", entries.size(), filename);

            if (logger.isDebugEnabled()) {
                long fileSize = Files.size(filePath);
                logger.debug("File statistics - Size: {} bytes, Entries: {}", fileSize, entries.size());
            }

        } catch (IOException e) {
            logger.error("Failed to write properties file: {}. Error: {}", filename, e.getMessage(), e);
            throw new IOException("Error writing properties file: " + filename, e);
        }
    }
}