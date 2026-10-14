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

package org.apache.ofbiz.finance.lease;

import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Settings of a lease payment component type (STEP L11, seed
 * {@code LeaseComponentRevenueAccount}): the revenue account of a separate
 * (区分) component and whether the component must be separate (the service part
 * of a maintenance lease is never folded into the method-1 sales).
 */
public final class LeaseComponentSettings {

    private LeaseComponentSettings() {
    }

    private static GenericValue row(Delegator delegator, String componentTypeEnumId) throws GenericEntityException {
        if (UtilValidate.isEmpty(componentTypeEnumId)) {
            return null;
        }
        return EntityQuery.use(delegator).from("LeaseComponentRevenueAccount")
                .where("componentTypeEnumId", componentTypeEnumId).cache().queryOne();
    }

    /** GL account type credited by a separate component (default: lease component revenue, 403800). */
    public static String revenueAccountType(Delegator delegator, String componentTypeEnumId) throws GenericEntityException {
        GenericValue row = row(delegator, componentTypeEnumId);
        return row != null && UtilValidate.isNotEmpty(row.getString("glAccountTypeId"))
                ? row.getString("glAccountTypeId") : LeaseRevenueStrategy.GL_COMPONENT_REVENUE;
    }

    /** True when the component type must be billed as separate revenue (含める is rejected). */
    public static boolean separateRequired(Delegator delegator, String componentTypeEnumId) throws GenericEntityException {
        GenericValue row = row(delegator, componentTypeEnumId);
        return row != null && "Y".equals(row.getString("separateRequired"));
    }
}
