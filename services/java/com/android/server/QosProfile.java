/* Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server;

import com.android.internal.telephony.QosSpec;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import android.util.Log;
import android.util.Pair;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class QosProfile {
    private static final boolean DBG = true;

    private static final String TAG = "QoSProfile";

    // XML entity definitions
    static final String QOS_POLICY = "qosPolicy";
    static final String ROLES_LIST = "rolesList";
    static final String ROLE = "role";
    static final String RAT = "RAT";
    static final String QOS_SPEC = "QosSpec";
    static final String QOS_FLOW_FILTER = "QosFlowFilter";
    static final String ID = "id";

    // Hash map of QoS specifications keyed by the pair of QoS role and RAT
    private Map<Pair<String, Integer>, QosSpec> mQoSProfileList;

    public QosProfile() {
        super();
        mQoSProfileList = new HashMap<Pair<String, Integer>, QosSpec>();

    }

    /**
     * Parses an QoS profile into a map of QoS Specs
     *
     * @param xmlStream The QoS profile.
     * @return boolean: true on success, false on error
     */
    public boolean parse(InputStream xmlStream) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        QosSpec qosSpec = null;

        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document dom = builder.parse(xmlStream);
            Element root = dom.getDocumentElement();

            // Find all role elements
            NodeList roles = root.getElementsByTagName(ROLE);
            for (int i = 0; i < roles.getLength(); i++) {
                Node role = roles.item(i);
                String roleId = role.getAttributes().getNamedItem(ID).getNodeValue();

                // For each role element find RAT elements
                NodeList rats = ((Element) role).getElementsByTagName(RAT);
                for (int j = 0; j < rats.getLength(); j++) {
                    Node rat = rats.item(j);
                    int ratId = Integer.parseInt(rat.getAttributes().getNamedItem(ID)
                            .getNodeValue());
                    qosSpec = new QosSpec();
                    if (DBG) Log.v(TAG, "Creating QosSPec: " + j);
                    if (DBG) Log.v(TAG, "Role id:" + roleId + " RAT id:" + ratId);

                    // For each RAT element find the QosFlowFilter elements
                    NodeList qosSpecs = ((Element) rat).getElementsByTagName(QOS_SPEC);
                    NodeList qosFlowFilters = ((Element) qosSpecs.item(0))
                            .getElementsByTagName(QOS_FLOW_FILTER);
                    for (int k = 0; k < qosFlowFilters.getLength(); k++) {
                        Node qosFlowFilter = qosFlowFilters.item(k);

                        // For each QosFlowFilter element create a QoS pipe and
                        // populate it
                        if (DBG) Log.v(TAG, "Creating QoSPipe: " + k);
                        QosSpec.QosPipe pipe = qosSpec.createPipe();
                        NodeList qosFields = qosFlowFilter.getChildNodes();
                        for (int l = 0; l < qosFields.getLength(); l++) {
                            Node qosField = qosFields.item(l);
                            if (qosField.getNodeType() == Node.ELEMENT_NODE) {
                                String key = qosField.getNodeName();
                                String value = qosField.getFirstChild().getNodeValue();
                                if (DBG) Log.v(TAG, "key:" + key + " value:" + value);
                                pipe.put(QosSpec.QosSpecKey.getKey(key), value);
                            }
                        }
                    }

                    // Add the QoS spec in the map for the given RAT and role
                    // IDs
                    mQoSProfileList.put(Pair.create(roleId, ratId), qosSpec);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception while parsing the QoS policy file: " + e.toString());
            e.printStackTrace();
            return false;
        }

        return true;
    }

    /**
     * This function returns the QoS spec given the role ID and RAT ID
     *
     * @param roleId
     * @param ratId
     * @return QoSSpec if found, null otherwise
     */
    public QosSpec getQoSSpec(String roleId, int ratId) {
        QosSpec qosSpec = mQoSProfileList.get(Pair.create(roleId, ratId));

        if (qosSpec != null) {
            return new QosSpec(qosSpec); // Return a copy of the QosSpec
        } else {
            // If no RAT specific QoSSpec is found then return null
            if (DBG) Log.v(TAG, "No QoSSpec found for RAT id:" + ratId);
            return null;
        }
    }
}
