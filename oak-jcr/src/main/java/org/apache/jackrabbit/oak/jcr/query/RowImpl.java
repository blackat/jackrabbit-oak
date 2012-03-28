/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.jcr.query;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Row;
import org.apache.jackrabbit.oak.query.CoreValue;
import org.apache.jackrabbit.oak.query.ResultRow;

/**
 * The implementation of the corresponding JCR interface.
 */
public class RowImpl implements Row {

    private final ResultRow row;

    public RowImpl(ResultRow row) {
        this.row = row;
    }

    public Node getNode() throws RepositoryException {
        // TODO row node
        return null;
    }

    public Node getNode(String selectorName) throws RepositoryException {
        // TODO row node
        return null;
    }

    public String getPath() throws RepositoryException {
        return row.getPath();
    }

    public String getPath(String selectorName) throws RepositoryException {
        return row.getPath(selectorName);
    }

    public double getScore() throws RepositoryException {
        // TODO row score
        return 0;
    }

    public double getScore(String selectorName) throws RepositoryException {
        // TODO row score
        return 0;
    }

    public Value getValue(String columnName) throws ItemNotFoundException, RepositoryException {
        return ValueConverter.convert(row.getValue(columnName));
    }

    public Value[] getValues() throws RepositoryException {
        CoreValue[] values = row.getValues();
        int len = values.length;
        Value[] v2 = new Value[values.length];
        for (int i = 0; i < len; i++) {
            v2[i] = ValueConverter.convert(values[i]);
        }
        return v2;
    }

}
