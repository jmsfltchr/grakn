/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.reasoner.resolution.framework;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

public final class ResolutionLogger {

    private static final Logger LOG = LoggerFactory.getLogger(ResolutionLogger.class);

    private static ResolutionLogger INSTANCE;
    private static PrintWriter writer;
    private static int rootRequestNumber = 0;
    private static int messageNumber = 0;
    private static AtomicReference<Path> path = new AtomicReference<>(null);

    private final String DOUBLE_QUOTE = "\"";

    private ResolutionLogger() {}

    public static ResolutionLogger get() {
        if(INSTANCE == null) {
            INSTANCE = new ResolutionLogger();
        }
        return INSTANCE;
    }

    void request(Resolver<?> sender, Resolver<?> receiver, int iteration, String conceptMap) {
        addMessage(sender, receiver, iteration, EdgeType.REQUEST, conceptMap);
    }

    void responseAnswer(Resolver<?> sender, Resolver<?> receiver, int iteration, String conceptMap) {
        addMessage(sender, receiver, iteration, EdgeType.ANSWER, conceptMap);
    }

    void responseExhausted(Resolver<?> sender, Resolver<?> receiver, int iteration) {
        addMessage(sender, receiver, iteration, EdgeType.EXHAUSTED, "");
    }

    private void addMessage(Resolver<?> sender, Resolver<?> receiver, int iteration, EdgeType edgeType, String conceptMap) {
        if (path.get() == null) initialise();
        writeEdge(sender.name(), receiver.name(), iteration, edgeType.colour(), messageNumber, conceptMap);
        messageNumber ++;
    }

    private void writeEdge(String fromId, String toId, int iteration, String colour, int messageNumber, String conceptMap) {
        write(String.format("%s -> %s [style=bold,label=%s,color=%s];",
                            doubleQuotes(escapeNewlines(escapeDoubleQuotes(fromId))),
                            doubleQuotes(escapeNewlines(escapeDoubleQuotes(toId))),
                            doubleQuotes("m" + messageNumber + "_it" + iteration + "_" + conceptMap),
                            doubleQuotes(colour)));

    }

    private String escapeNewlines(String toFormat) {
        return toFormat.replaceAll("\n", "\\\\l") + "\\l";
    }

    private String escapeDoubleQuotes(String toFormat) {
        return toFormat.replaceAll("\"", "\\\\\"");
    }

    private String doubleQuotes(String toFormat) {
        return DOUBLE_QUOTE + toFormat + DOUBLE_QUOTE;
    }

    private void initialise() {
        messageNumber = 0;
        path.set(Paths.get("./" + name() + ".dot"));
        try {
            writer = new PrintWriter(path.get().toFile(), "UTF-8");
            startFile();
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            e.printStackTrace();
            LOG.trace("Resolution logging failed to start writing");
        }
    }

    private void startFile() {
        write(String.format(
                "digraph %s {\n" +
                        "node [fontsize=12 fontname=arial width=0.5 shape=box style=filled]\n" +
                        "edge [fontsize=10 fontname=arial width=0.5]",
                name()));
    }

    private String name() {
        return String.format("resolution_log_request_%d", rootRequestNumber);
    }

    private void endFile() {
        write("}");
    }

    private void write(String toWrite) {
        writer.println(toWrite);
    }

    public void finish() {
        endFile();
        try {
            LOG.trace("Resolution log written to {}", path.get().toAbsolutePath());
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
            LOG.trace("Resolution logging failed to write");
        }
        rootRequestNumber += 1;
        path.set(null);
    }

    enum EdgeType {
        EXHAUSTED("red"),
        ANSWER("green"),
        REQUEST("blue");

        private final String colour;

        EdgeType(String colour) {
            this.colour = colour;
        }

        public String colour() {
            return colour;
        }
    }

}
