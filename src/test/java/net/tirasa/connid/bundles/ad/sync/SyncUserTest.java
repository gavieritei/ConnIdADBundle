/* 
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2011 ConnId. All rights reserved.
 * 
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 * 
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 * 
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 */
package net.tirasa.connid.bundles.ad.sync;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapContext;
import net.tirasa.connid.bundles.ad.ADConfiguration;
import net.tirasa.connid.bundles.ad.ADConnection;
import net.tirasa.connid.bundles.ad.ADConnector;
import net.tirasa.connid.bundles.ad.UserTest;
import net.tirasa.connid.bundles.ad.util.DirSyncUtils;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.impl.api.APIConfigurationImpl;
import org.identityconnectors.framework.impl.api.local.JavaClassProperties;
import org.identityconnectors.test.common.TestHelpers;
import org.junit.Test;

public class SyncUserTest extends UserTest {

    @Test
    public void sync() {
        // We need to have several operation in the right sequence in order
        // to verify synchronization ...

        // ----------------------------------
        // Handler specification
        // ----------------------------------

        final List<SyncDelta> updated = new ArrayList<SyncDelta>();
        final List<SyncDelta> deleted = new ArrayList<SyncDelta>();

        final SyncResultsHandler handler = new SyncResultsHandler() {

            @Override
            public boolean handle(final SyncDelta sd) {
                if (sd.getDeltaType() == SyncDeltaType.DELETE) {
                    return deleted.add(sd);
                } else {
                    return updated.add(sd);
                }
            }
        };
        // ----------------------------------

        // Ask just for sAMAccountName
        final OperationOptionsBuilder oob = new OperationOptionsBuilder();
        oob.setAttributesToGet(Arrays.asList(new String[] { "sAMAccountName", "givenName", "memberOf" }));

        SyncToken token = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
        connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());

        assertTrue(deleted.isEmpty());
        assertTrue(updated.isEmpty());

        final Map.Entry<String, String> ids11 = util.getEntryIDs("11");
        final Map.Entry<String, String> ids12 = util.getEntryIDs("12");

        Uid uid11 = null;
        Uid uid12 = null;

        try {
            // ----------------------------------
            // check sync with new user (token updated)
            // ----------------------------------
            // user added sync
            uid11 = connector.create(ObjectClass.ACCOUNT, util.getSimpleProfile(ids11), null);

            updated.clear();
            deleted.clear();

            SyncToken nextToken = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());

            // user creation and group modification
            assertEquals(3, updated.size());

            final ConnectorObject obj = updated.get(0).getObject();

            // chek for returned attributes
            assertEquals(5, updated.get(0).getObject().getAttributes().size());
            assertNotNull(obj.getAttributeByName("sAMAccountName"));
            assertNotNull(obj.getAttributeByName("givenName"));
            assertNotNull(obj.getAttributeByName("__NAME__"));
            assertNotNull(obj.getAttributeByName("__UID__"));
            assertNotNull(obj.getAttributeByName("memberOf"));
            assertEquals(ids11.getValue(), updated.get(0).getUid().getUidValue());

            updated.clear();
            deleted.clear();

            // check with updated token and without any modification
            nextToken = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertTrue(updated.isEmpty());
            // ----------------------------------

            // ----------------------------------
            // check sync with user 'IN' group (token updated)
            // ----------------------------------
            // created a new user without memberships specification
            final ADConfiguration configuration = getSimpleConf(prop);
            configuration.setMemberships();

            if (LOG.isOk()) {
                LOG.ok("\n Configuration: {0}\n Filter: {1}",
                        configuration,
                        DirSyncUtils.createLdapUFilter(configuration));
            }

            final ADConnection connection = new ADConnection(configuration);
            final LdapContext ctx = connection.getInitialContext();

            final Attributes attrs = new BasicAttributes(true);
            attrs.put(new BasicAttribute("cn", ids12.getKey()));
            attrs.put(new BasicAttribute("sn", ids12.getKey()));
            attrs.put(new BasicAttribute("givenName", ids12.getKey()));
            attrs.put(new BasicAttribute("displayName", ids12.getKey()));
            attrs.put(new BasicAttribute("sAMAccountName", ids12.getValue()));
            attrs.put(new BasicAttribute("userPrincipalName", "test@test.org"));
            attrs.put(new BasicAttribute("userPassword", "password"));
            attrs.put(new BasicAttribute("objectClass", "top"));
            attrs.put(new BasicAttribute("objectClass", "person"));
            attrs.put(new BasicAttribute("objectClass", "organizationalPerson"));
            attrs.put(new BasicAttribute("objectClass", "user"));

            try {

                ctx.createSubcontext(
                        "CN=" + ids12.getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0], attrs);
                uid12 = new Uid(ids12.getValue());

            } catch (NamingException e) {
                LOG.error(e, "Error creating user {0}", ids12.getValue());
                assert (false);
            }

            updated.clear();
            deleted.clear();

            nextToken = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertTrue(updated.isEmpty());

            ModificationItem[] mod =
                    new ModificationItem[] { new ModificationItem(
                                DirContext.ADD_ATTRIBUTE,
                                new BasicAttribute("member",
                                        "CN=" + ids12.getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0]))
                    };

            try {
                ctx.modifyAttributes(conf.getMemberships()[0], mod);
            } catch (NamingException e) {
                LOG.error(e, "Error adding membership to {0}", ids12.getValue());
                assert (false);
            }

            updated.clear();
            deleted.clear();

            nextToken = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertEquals(1, updated.size());

            mod = new ModificationItem[] { new ModificationItem(
                DirContext.ADD_ATTRIBUTE,
                new BasicAttribute("member",
                "CN=" + ids12.getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0]))
            };

            try {
                ctx.modifyAttributes(conf.getMemberships()[1], mod);
            } catch (NamingException e) {
                LOG.error(e, "Error adding membership to {0}", ids12.getValue());
                assert (false);
            }

            updated.clear();
            deleted.clear();

            nextToken = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertEquals(1, updated.size());
            // ----------------------------------

            // ----------------------------------
            // check sync with user 'OUT' group (token updated)
            // ----------------------------------
            mod = new ModificationItem[] {
                new ModificationItem(
                DirContext.REMOVE_ATTRIBUTE,
                new BasicAttribute("member",
                "CN=" + ids12.getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0]))
            };

            try {
                ctx.modifyAttributes(conf.getMemberships()[0], mod);
            } catch (NamingException e) {
                LOG.error(e, "Error adding membership to {0}", ids12.getValue());
                assert (false);
            }

            updated.clear();
            deleted.clear();

            // sync user delete (member out is like a user delete)
            conf.setRetrieveDeletedUser(true);

            final ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();

            final APIConfiguration impl = TestHelpers.createTestConfiguration(ADConnector.class, conf);
            // TODO: remove the line below when using ConnId >= 1.4.0.1
            ((APIConfigurationImpl) impl).
                    setConfigurationProperties(JavaClassProperties.createConfigurationProperties(conf));

            final ConnectorFacade newConnector = factory.newInstance(impl);

            nextToken = newConnector.getLatestSyncToken(ObjectClass.ACCOUNT);
            newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertEquals(1, updated.size());
            assertNotNull(updated.get(0).getObject().getAttributeByName("memberOf"));
            assertNotNull(updated.get(0).getObject().getAttributeByName("memberOf").getValue());
            assertEquals(1, updated.get(0).getObject().getAttributeByName("memberOf").getValue().size());

            // add user to a group not involved into the filter
            mod = new ModificationItem[] {
                new ModificationItem(
                DirContext.ADD_ATTRIBUTE,
                new BasicAttribute("member",
                "CN=" + ids12.getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0]))
            };

            try {
                ctx.modifyAttributes("CN=Cert Publishers,CN=Users," + conf.getBaseContextsToSynchronize()[0], mod);
            } catch (NamingException e) {
                LOG.error(e, "Error adding membership to {0}", ids12.getValue());
                assert (false);
            }

            updated.clear();
            deleted.clear();

            nextToken = newConnector.getLatestSyncToken(ObjectClass.ACCOUNT);
            newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertEquals(1, updated.size());
            assertNotNull(updated.get(0).getObject().getAttributeByName("memberOf"));
            assertNotNull(updated.get(0).getObject().getAttributeByName("memberOf").getValue());
            assertEquals(2, updated.get(0).getObject().getAttributeByName("memberOf").getValue().size());

            mod = new ModificationItem[] {
                new ModificationItem(
                DirContext.REMOVE_ATTRIBUTE,
                new BasicAttribute("member",
                "CN=" + ids12.getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0]))
            };

            try {
                ctx.modifyAttributes(conf.getMemberships()[1], mod);
            } catch (NamingException e) {
                LOG.error(e, "Error adding membership to {0}", ids12.getValue());
                assert (false);
            }

            updated.clear();
            deleted.clear();

            nextToken = newConnector.getLatestSyncToken(ObjectClass.ACCOUNT);
            newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(updated.isEmpty());
            assertEquals(1, deleted.size());
            // ----------------------------------

            // ----------------------------------
            // check sync with updated user (token updated)
            // ----------------------------------
            // user modify sync
            uid11 = connector.update(
                    ObjectClass.ACCOUNT, uid11,
                    Collections.singleton(AttributeBuilder.build(
                                    "givenName", Collections.singleton("changed"))),
                    null);

            updated.clear();
            deleted.clear();

            nextToken = connector.getLatestSyncToken(ObjectClass.ACCOUNT);
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = nextToken;

            assertTrue(deleted.isEmpty());
            assertEquals(1, updated.size());

            updated.clear();
            deleted.clear();

            // check with updated token and without any modification
            connector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());

            assertTrue(deleted.isEmpty());
            assertTrue(updated.isEmpty());
            // ----------------------------------
        } finally {
            if (uid12 != null) {
                connector.delete(ObjectClass.ACCOUNT, uid12, null);
            }

            if (uid11 != null) {
                // user delete sync
                conf.setRetrieveDeletedUser(true);

                final ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
                final APIConfiguration impl = TestHelpers.createTestConfiguration(ADConnector.class, conf);
                // TODO: remove the line below when using ConnId >= 1.4.0.1
                ((APIConfigurationImpl) impl).
                        setConfigurationProperties(JavaClassProperties.createConfigurationProperties(conf));

                final ConnectorFacade newConnector = factory.newInstance(impl);

                token = newConnector.getLatestSyncToken(ObjectClass.ACCOUNT);

                newConnector.delete(ObjectClass.ACCOUNT, uid11, null);

                updated.clear();
                deleted.clear();

                newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());

                assertFalse(deleted.isEmpty());
                assertTrue(deleted.size() <= 2);
                assertTrue(deleted.get(0).getUid().getUidValue().startsWith(util.getEntryIDs("1").getValue()));
            }
        }
    }

    @Test
    public void initialLoading() {
        // We need to have several operation in the right sequence in order
        // to verify synchronization ...

        // ----------------------------------
        // Handler specification
        // ----------------------------------

        final List<SyncDelta> updated = new ArrayList<SyncDelta>();
        final List<SyncDelta> deleted = new ArrayList<SyncDelta>();

        final SyncResultsHandler handler = new SyncResultsHandler() {

            @Override
            public boolean handle(final SyncDelta sd) {
                if (sd.getDeltaType() == SyncDeltaType.DELETE) {
                    return deleted.add(sd);
                } else {
                    return updated.add(sd);
                }
            }
        };
        // ----------------------------------
        final ADConfiguration newconf = getSimpleConf(prop);
        newconf.setRetrieveDeletedUser(false);
        newconf.setLoading(true);

        // Ask just for sAMAccountName
        final OperationOptionsBuilder oob = new OperationOptionsBuilder();
        oob.setAttributesToGet(Arrays.asList(new String[] { "sAMAccountName", "givenName" }));

        SyncToken token = null;

        ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        APIConfiguration impl = TestHelpers.createTestConfiguration(ADConnector.class, newconf);
        // TODO: remove the line below when using ConnId >= 1.4.0.1
        ((APIConfigurationImpl) impl).
                setConfigurationProperties(JavaClassProperties.createConfigurationProperties(newconf));

        ConnectorFacade newConnector = factory.newInstance(impl);

        // ----------------------------------
        // check initial loading
        // ----------------------------------
        newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());

        assertTrue(deleted.isEmpty());

        // Since DirSync search is paginated we must loop on sync until returned
        // handles will be empty
        int count = 0;
        while (count < 5) {
            updated.clear();
            deleted.clear();

            newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            token = newConnector.getLatestSyncToken(ObjectClass.ACCOUNT);

            if (updated.isEmpty() && deleted.isEmpty()) {
                count++;
            } else {
                count = 0;
            }
        }

        // ----------------------------------
        // check sync with new user (token updated)
        // ----------------------------------
        Map.Entry<String, String> ids13 = util.getEntryIDs("13");

        Uid uid13 = null;

        try {
            // user added sync
            uid13 = newConnector.create(ObjectClass.ACCOUNT, util.getSimpleProfile(ids13), null);

            updated.clear();
            deleted.clear();

            newConnector.sync(ObjectClass.ACCOUNT, token, handler, oob.build());
            assertTrue(deleted.isEmpty());
            assertEquals(1, updated.size());
        } finally {
            if (uid13 != null) {
                connector.delete(ObjectClass.ACCOUNT, uid13, null);
            }
        }
        // ----------------------------------
    }

    @Test
    public void verifyObjectGUID() {
        // Ask just for objectGUID
        final OperationOptionsBuilder oob = new OperationOptionsBuilder();
        oob.setAttributesToGet(Collections.singleton("objectGUID"));

        final ConnectorObject object =
                connector.getObject(ObjectClass.ACCOUNT, new Uid(util.getEntryIDs("4").getValue()), oob.build());

        assertNotNull(object);

        final Attribute objectGUID = object.getAttributeByName("objectGUID");
        assertNotNull(objectGUID);
        assertNotNull(objectGUID.getValue());
        assertEquals(1, objectGUID.getValue().size());

        assertTrue(objectGUID.getValue().get(0) instanceof String);
        assertFalse(String.class.cast(objectGUID.getValue().get(0)).isEmpty());

        if (LOG.isOk()) {
            LOG.ok("ObjectGUID (String): {0}", objectGUID.getValue().get(0));
        }
    }

    @Test
    public void verifyFilter() {
        // instatiate a new configuration to avoid collisions with sync test
        final ADConfiguration configuration = getSimpleConf(prop);

        final String DN =
                "CN=" + util.getEntryIDs("5").getKey() + ",CN=Users," + configuration.getUserBaseContexts()[0];

        final ADConnection connection = new ADConnection(configuration);
        final LdapContext ctx = connection.getInitialContext();

        assertTrue(DirSyncUtils.verifyCustomFilter(ctx, DN, configuration));

        configuration.setAccountSearchFilter("(&(Objectclass=user)(cn=" + util.getEntryIDs("5").getKey() + "))");
        assertTrue(DirSyncUtils.verifyCustomFilter(ctx, DN, configuration));

        configuration.setAccountSearchFilter("(&(Objectclass=user)(cn=" + util.getEntryIDs("6").getKey() + "))");
        assertFalse(DirSyncUtils.verifyCustomFilter(ctx, DN, configuration));
    }

    @Test
    public void verifySearchWithMemb() {

        final ADConfiguration confWithMembership = getSimpleConf(prop);
        confWithMembership.setMemberships("CN=GroupTestInFilter,CN=Users," + BASE_CONTEXT);

        final ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        final APIConfiguration impl = TestHelpers.createTestConfiguration(ADConnector.class, confWithMembership);
        ((APIConfigurationImpl) impl).
                setConfigurationProperties(JavaClassProperties.createConfigurationProperties(confWithMembership));

        final ConnectorFacade newConnector = factory.newInstance(impl);

        List<Attribute> attrToReplace = Arrays.asList(new Attribute[] {
            AttributeBuilder.build("ldapGroups", "CN=GroupTestInFilter,CN=Users," + BASE_CONTEXT) });

        Uid uid = connector.update(
                ObjectClass.ACCOUNT,
                new Uid(util.getEntryIDs("4").getValue()),
                new HashSet<Attribute>(attrToReplace),
                null);

        assertNotNull(uid);

        final List<Attribute> results = new ArrayList<Attribute>();

        final ResultsHandler handler = new ResultsHandler() {

            @Override
            public boolean handle(ConnectorObject co) {
                return results.add(co.getAttributeByName("sAMAccountName"));
            }
        };

        // Ask just for sAMAccountName
        final OperationOptionsBuilder oob = new OperationOptionsBuilder();
        oob.setAttributesToGet(Collections.singletonList("sAMAccountName"));

        newConnector.search(ObjectClass.ACCOUNT, null, handler, oob.build());
        
        assertNotNull(results);
        assertTrue(results.size() == 1);
    }
}
