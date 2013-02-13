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
package org.apache.jackrabbit.oak.security.privilege;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.jcr.RepositoryException;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.core.ReadOnlyRoot;
import org.apache.jackrabbit.oak.core.ReadOnlyTree;
import org.apache.jackrabbit.oak.plugins.name.NamespaceConstants;
import org.apache.jackrabbit.oak.spi.commit.Validator;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeDefinition;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validator implementation that is responsible for validating any modifications
 * made to privileges stored in the repository.
 */
class PrivilegeValidator implements PrivilegeConstants, Validator {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeValidator.class);

    private final PrivilegeDefinitionStore storeBefore;
    private final PrivilegeDefinitionStore storeAfter;

    PrivilegeValidator(NodeState before, NodeState after) {
        storeBefore = new PrivilegeDefinitionStore(new ReadOnlyRoot(before));
        storeAfter = new PrivilegeDefinitionStore((new ReadOnlyRoot(after)));
    }

    //----------------------------------------------------------< Validator >---
    @Override
    public void propertyAdded(PropertyState after) throws CommitFailedException {
        // no-op
    }

    @Override
    public void propertyChanged(PropertyState before, PropertyState after) throws CommitFailedException {
        if (REP_NEXT.equals(before.getName())) {
            validateNext(PrivilegeBits.getInstance(storeBefore.getPrivilegesTree().getProperty(REP_NEXT)));
        } else {
            throw new CommitFailedException("Attempt to modify existing privilege definition.");
        }
    }

    @Override
    public void propertyDeleted(PropertyState before) throws CommitFailedException {
        throw new CommitFailedException("Attempt to modify existing privilege definition.");
    }

    @Override
    public Validator childNodeAdded(String name, NodeState after) throws CommitFailedException {
        checkInitialized();
        // the following characteristics are expected to be validated elsewhere:
        // - permission to allow privilege registration -> permission validator.
        // - name collisions (-> delegated to NodeTypeValidator since sms are not allowed)
        // - name must be valid (-> delegated to NameValidator)

        // name may not contain reserved namespace prefix
        if (NamespaceConstants.RESERVED_PREFIXES.contains(Text.getNamespacePrefix(name))) {
            String msg = "Failed to register custom privilege: Definition uses reserved namespace: " + name;
            throw new CommitFailedException(new RepositoryException(msg));
        }

        // primary node type name must be rep:privilege
        Tree tree = new ReadOnlyTree(null, name, after);
        PropertyState primaryType = tree.getProperty(JcrConstants.JCR_PRIMARYTYPE);
        if (primaryType == null || !NT_REP_PRIVILEGE.equals(primaryType.getValue(Type.STRING))) {
            throw new CommitFailedException("Privilege definition must have primary node type set to rep:privilege");
        }

        // additional validation of the definition
        validateDefinition(tree);

        // privilege definitions may not have child nodes.
        return null;
    }

    @Override
    public Validator childNodeChanged(String name, NodeState before, NodeState after) throws CommitFailedException {
        throw new CommitFailedException("Attempt to modify existing privilege definition " + name);
    }

    @Override
    public Validator childNodeDeleted(String name, NodeState before) throws CommitFailedException {
        throw new CommitFailedException("Attempt to un-register privilege " + name);
    }

    //------------------------------------------------------------< private >---
    private void checkInitialized() throws CommitFailedException {
        if (storeBefore.getPrivilegesTree() == null) {
            throw new CommitFailedException("Privilege store not initialized.");
        }
    }

    private void validateNext(PrivilegeBits bits) throws CommitFailedException {
        PrivilegeBits next = PrivilegeBits.getInstance(storeAfter.getPrivilegesTree().getProperty(REP_NEXT));
        if (!next.equals(bits.nextBits())) {
            throw new CommitFailedException("Next bits not updated.");
        }
    }

    private void validateBits(PrivilegeBits bits, PrivilegeBits expectedNext) throws CommitFailedException {
        if (!expectedNext.equals(bits.nextBits())) {
            throw new CommitFailedException("PrivilegeBits violation: Expected " + expectedNext + "; Found" + bits + '.');
        }
    }

    /**
     * Validation of the privilege definition including the following steps:
     * <p/>
     * - privilege bits must not collide with an existing privilege
     * - next bits must have been adjusted in case of a non-aggregate privilege
     * - all aggregates must have been registered before
     * - no existing privilege defines the same aggregation
     * - no cyclic aggregation
     *
     * @param definitionTree The new privilege definition tree to validate.
     * @throws org.apache.jackrabbit.oak.api.CommitFailedException
     *          If any of
     *          the checks listed above fails.
     */
    private void validateDefinition(Tree definitionTree) throws CommitFailedException {
        PrivilegeBits newBits = PrivilegeBits.getInstance(definitionTree);
        if (newBits.isEmpty()) {
            throw new CommitFailedException("PrivilegeBits are missing.");
        }

        Set<String> privNames = storeBefore.getPrivilegeNames(newBits);
        PrivilegeDefinition definition = PrivilegeDefinitionStore.readDefinition(definitionTree);
        Set<String> declaredNames = definition.getDeclaredAggregateNames();

        // non-aggregate privilege
        if (declaredNames.isEmpty()) {
            if (!privNames.isEmpty()) {
                throw new CommitFailedException("PrivilegeBits already in used.");
            }
            validateNext(newBits);
            return;
        }

        // aggregation of a single privilege
        if (declaredNames.size() == 1) {
            throw new CommitFailedException("Singular aggregation is equivalent to existing privilege.");
        }

        // aggregation of >1 privileges
        Map<String, PrivilegeDefinition> definitions = storeBefore.readDefinitions();
        for (String aggrName : declaredNames) {
            // aggregated privilege not registered
            if (!definitions.containsKey(aggrName)) {
                throw new CommitFailedException("Declared aggregate '" + aggrName + "' is not a registered privilege.");
            }

            // check for circular aggregation
            if (isCircularAggregation(definition.getName(), aggrName, definitions)) {
                String msg = "Detected circular aggregation within custom privilege caused by " + aggrName;
                throw new CommitFailedException(msg);
            }
        }

        Set<String> aggregateNames = resolveAggregates(declaredNames, definitions);
        for (PrivilegeDefinition existing : definitions.values()) {
            Set<String> existingDeclared = existing.getDeclaredAggregateNames();
            if (existingDeclared.isEmpty()) {
                continue;
            }

            // test for exact same aggregation or aggregation with the same net effect
            if (declaredNames.equals(existingDeclared) || aggregateNames.equals(resolveAggregates(existingDeclared, definitions))) {
                String msg = "Custom aggregate privilege '" + definition.getName() + "' is already covered by '" + existing.getName() + '\'';
                throw new CommitFailedException(msg);
            }
        }

        PrivilegeBits aggrBits = storeBefore.getBits(declaredNames.toArray(new String[declaredNames.size()]));
        if (!newBits.equals(aggrBits)) {
            throw new CommitFailedException("Invalid privilege bits for aggregated privilege definition.");
        }
    }

    private static boolean isCircularAggregation(String privilegeName, String aggregateName,
                                                 Map<String, PrivilegeDefinition> definitions) {
        if (privilegeName.equals(aggregateName)) {
            return true;
        }

        PrivilegeDefinition aggrPriv = definitions.get(aggregateName);
        if (aggrPriv.getDeclaredAggregateNames().isEmpty()) {
            return false;
        } else {
            boolean isCircular = false;
            for (String name : aggrPriv.getDeclaredAggregateNames()) {
                if (privilegeName.equals(name)) {
                    return true;
                }
                if (definitions.containsKey(name)) {
                    isCircular = isCircularAggregation(privilegeName, name, definitions);
                }
            }
            return isCircular;
        }
    }

    private static Set<String> resolveAggregates(Set<String> declared, Map<String, PrivilegeDefinition> definitions) throws CommitFailedException {
        Set<String> aggregateNames = new HashSet<String>();
        for (String name : declared) {
            PrivilegeDefinition d = definitions.get(name);
            if (d == null) {
                throw new CommitFailedException("Invalid declared aggregate name " + name + ": Unknown privilege.");
            }

            Set<String> names = d.getDeclaredAggregateNames();
            if (names.isEmpty()) {
                aggregateNames.add(name);
            } else {
                aggregateNames.addAll(resolveAggregates(names, definitions));
            }
        }
        return aggregateNames;
    }
}
