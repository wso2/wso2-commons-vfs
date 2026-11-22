/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Records the log lines of one class while it is open. commons logging writes to Log4j 2 on the test class path.
 */
public final class LogCapture extends AbstractAppender implements AutoCloseable {

    private final Logger logger;

    private final Level before;

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Starts to record the lines of a class, from DEBUG up.
     *
     * @param type the class whose logger is recorded.
     */
    public LogCapture(final Class<?> type) {
        super("capture-" + type.getSimpleName(), null, null, true, Property.EMPTY_ARRAY);
        logger = (Logger) LogManager.getLogger(type.getName());
        before = logger.getLevel();
        start();
        logger.addAppender(this);
        logger.setLevel(Level.DEBUG);
    }

    @Override
    public void append(final LogEvent event) {
        events.add(event.toImmutable());
    }

    /**
     * Gets the messages of one level.
     *
     * @param level the level.
     * @return the messages, in order.
     */
    public List<String> messages(final Level level) {
        return events.stream().filter(event -> event.getLevel() == level)
                .map(event -> event.getMessage().getFormattedMessage()).collect(Collectors.toList());
    }

    @Override
    public void close() {
        logger.removeAppender(this);
        logger.setLevel(before);
        stop();
    }
}
