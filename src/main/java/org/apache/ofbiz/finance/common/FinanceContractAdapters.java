/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/

package org.apache.ofbiz.finance.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;

/**
 * Registry of {@link FinanceContractAdapter}s keyed by contract type.
 *
 * <p>Adapters are configured in {@code finance.properties} as
 * {@code contract.adapter.<contractTypeId>=<fully qualified class>} and loaded
 * reflectively, so the common layer has no compile-time dependency on the loan
 * or lease layer. A contract type without a configured adapter is skipped by the
 * common engines.
 */
public final class FinanceContractAdapters {

    private static final String MODULE = FinanceContractAdapters.class.getName();
    private static final String RESOURCE = "finance";
    private static final String KEY_PREFIX = "contract.adapter.";
    private static final String[] KNOWN_TYPES = {FinanceContractHelper.TYPE_LOAN, FinanceContractHelper.TYPE_LEASE};

    private static final Map<String, FinanceContractAdapter> CACHE = new ConcurrentHashMap<>();

    private FinanceContractAdapters() {
    }

    /** Adapter for a contract type, or null when none is configured. */
    public static FinanceContractAdapter forType(String contractTypeId) {
        if (UtilValidate.isEmpty(contractTypeId)) {
            return null;
        }
        FinanceContractAdapter cached = CACHE.get(contractTypeId);
        if (cached != null) {
            return cached;
        }
        String className = UtilProperties.getPropertyValue(RESOURCE, KEY_PREFIX + contractTypeId);
        if (UtilValidate.isEmpty(className)) {
            return null;
        }
        try {
            Class<?> clazz = Class.forName(className.trim(), true, Thread.currentThread().getContextClassLoader());
            FinanceContractAdapter adapter = (FinanceContractAdapter) clazz.getDeclaredConstructor().newInstance();
            CACHE.put(contractTypeId, adapter);
            return adapter;
        } catch (ReflectiveOperationException | ClassCastException e) {
            Debug.logError(e, "Cannot load finance contract adapter " + className + " for " + contractTypeId, MODULE);
            return null;
        }
    }

    /** All configured adapters. */
    public static Collection<FinanceContractAdapter> all() {
        List<FinanceContractAdapter> adapters = new ArrayList<>();
        for (String type : KNOWN_TYPES) {
            FinanceContractAdapter adapter = forType(type);
            if (adapter != null) {
                adapters.add(adapter);
            }
        }
        return adapters;
    }

    /**
     * Resolve the target contract of a service call: {@code financeContractId} if
     * given, otherwise the first adapter whose agreement id field is present in
     * the context (e.g. {@code loanAgreementId}).
     */
    public static String resolveContractId(Delegator delegator, Map<String, ?> context) throws GenericEntityException {
        Object direct = context.get("financeContractId");
        if (direct instanceof String && UtilValidate.isNotEmpty((String) direct)) {
            return (String) direct;
        }
        for (FinanceContractAdapter adapter : all()) {
            Object agreementId = context.get(adapter.getAgreementIdField());
            if (agreementId instanceof String && UtilValidate.isNotEmpty((String) agreementId)) {
                return adapter.findContractIdByAgreementId(delegator, (String) agreementId);
            }
        }
        return null;
    }

    /** The agreement id given in the context (for error messages), or null. */
    public static String requestedAgreementId(Map<String, ?> context) {
        for (FinanceContractAdapter adapter : all()) {
            Object agreementId = context.get(adapter.getAgreementIdField());
            if (agreementId instanceof String && UtilValidate.isNotEmpty((String) agreementId)) {
                return (String) agreementId;
            }
        }
        Object direct = context.get("financeContractId");
        return direct instanceof String ? (String) direct : null;
    }
}
