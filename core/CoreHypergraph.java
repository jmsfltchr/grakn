/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package hypergraph.core;

import hypergraph.Hypergraph;
import hypergraph.common.exception.HypergraphException;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Hypergraph implementation with RocksDB
 */
public class CoreHypergraph implements Hypergraph {

    static {
        RocksDB.loadLibrary();
    }

    private final Path directory;
    private final Options rocksOptions;
    private final AtomicBoolean isOpen;
    private final CoreProperties properties;
    private final CoreKeyspaceManager keyspaceMgr;

    private CoreHypergraph(String directory, Properties properties) {
        this.directory = Paths.get(directory);
        this.properties = new CoreProperties(properties);

        rocksOptions = new Options().setCreateIfMissing(true);
        setOptionsFromProperties();

        keyspaceMgr = new CoreKeyspaceManager(this);
        keyspaceMgr.loadAll();

        isOpen = new AtomicBoolean();
        isOpen.set(true);
    }

    public static CoreHypergraph open(String directory) {
        return open(directory, new Properties());
    }

    public static CoreHypergraph open(String directory, Properties properties) {
        return new CoreHypergraph(directory, properties);
    }

    private void setOptionsFromProperties() {
        // TODO: configure optimisation paramaters
    }

    Path directory() {
        return directory;
    }

    CoreProperties properties() {
        return properties;
    }

    Options rocksOptions() {
        return rocksOptions;
    }

    @Override
    public CoreSession session(String keyspace, Hypergraph.Session.Type type) {
        if (keyspaceMgr.contains(keyspace)) {
            return keyspaceMgr.get(keyspace).createAndOpenSession(type);
        } else {
            throw new HypergraphException("There does not exists a keyspace with the name: " + keyspace);
        }
    }

    @Override
    public CoreKeyspaceManager keyspaces() {
        return keyspaceMgr;
    }

    @Override
    public boolean isOpen() {
        return this.isOpen.get();
    }

    @Override
    public void close() {
        if (isOpen.compareAndSet(true, false)) {
            keyspaceMgr.getAll().parallelStream().forEach(CoreKeyspace::close);
            rocksOptions.close();
        }
    }
}
