package com.gtp_demo_java.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PropertyEntry {
    private static final Logger logger = LoggerFactory.getLogger(PropertyEntry.class);

    String key;
    List<String> lines;
    EntryType type;

    public PropertyEntry(String key, List<String> lines, EntryType type) {
        logger.trace("Creating new PropertyEntry - Key: {}, Type: {}, Lines: {}",
                key, type, lines.size());

        this.key = key;
        this.lines = lines;
        this.type = type;

        if (logger.isDebugEnabled() && type == EntryType.PROPERTY) {
            logger.debug("Created property entry - Key: {}, Value: {}",
                    key, String.join("", lines));
        }
    }

    public enum EntryType {
        PROPERTY, COMMENT, EMPTY_LINE;

        @Override
        public String toString() {
            return name().toLowerCase().replace('_', ' ');
        }
    }
}