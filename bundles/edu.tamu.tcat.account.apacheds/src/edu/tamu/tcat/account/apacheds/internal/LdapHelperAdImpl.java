package edu.tamu.tcat.account.apacheds.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.directory.api.ldap.codec.api.LdapApiServiceFactory;
import org.apache.directory.api.ldap.codec.protocol.mina.LdapProtocolCodecFactory;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Value;
import org.apache.directory.api.ldap.model.message.AddRequest;
import org.apache.directory.api.ldap.model.message.AddRequestImpl;
import org.apache.directory.api.ldap.model.message.AddResponse;
import org.apache.directory.api.ldap.model.message.ModifyRequest;
import org.apache.directory.api.ldap.model.message.ModifyRequestImpl;
import org.apache.directory.api.ldap.model.message.ModifyResponse;
import org.apache.directory.api.ldap.model.message.ResultCodeEnum;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;

import edu.tamu.tcat.account.apacheds.LdapAuthException;
import edu.tamu.tcat.account.apacheds.LdapException;
import edu.tamu.tcat.account.apacheds.LdapHelperMutator;
import edu.tamu.tcat.account.apacheds.LdapHelperReader;

/** Turn this into a declarative service that binds to configuration */
public class LdapHelperAdImpl implements LdapHelperReader, LdapHelperMutator
{
   private static final Logger logger = Logger.getLogger(LdapHelperAdImpl.class.getName());
   private LdapConnectionConfig config = null;
   private String defaultSearchOu;

   /**
    * must be called after configure and before any queries
    */
   public void init()
   {
      if(config == null)
         throw new IllegalStateException("Configure must be called before init");
      // as of 1.0.0.RC2  default factory does not properly instantiate due to dependency issues 
      if (!LdapApiServiceFactory.isInitialized())
         try
         {
            StandaloneLdapApiService svc = new StandaloneLdapApiService();
            if (svc.getProtocolCodecFactory() == null)
               svc.registerProtocolCodecFactory(new LdapProtocolCodecFactory());
            LdapApiServiceFactory.initialize(svc);
            LdapApiServiceFactory.getSingleton();
         }
         catch (Exception e)
         {
            throw new IllegalStateException("Unable to instantiate Ldap API Service.", e);
         }
   }

   /** must be called before init */
   public void configure(String ip, int port, String userDn, String userPassword, boolean useSsl, boolean useTls, String defaultSearchOu)
   {
      config = new LdapConnectionConfig();
      config.setLdapHost(ip);
      config.setLdapPort(port);
      config.setName(userDn);
      config.setCredentials(userPassword);
      config.setUseSsl(useSsl);
      config.setUseTls(useTls);
      this.defaultSearchOu = defaultSearchOu;
   }

   // this may be replaced with getMatches
   protected String findDistinguishingName(String ouSearchPrefix, String otherName) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try(ClosableCursor c = new ClosableCursor(connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
         {
            EntryCursor cursor = c.cursor;
            String found = StreamSupport.stream(cursor.spliterator(), false)
               .filter(entry -> entry.contains("sAMAccountName", otherName) || entry.contains("userPrincipleName", otherName))
               .map(entry -> String.valueOf(entry.get("distinguishedName").get()))
               .findAny()
               .orElseThrow(() -> new LdapAuthException("No such user " + otherName + " in " + ouSearchPrefix));
            return found;
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed finding distinguished name " + otherName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public void checkValidUser(String user) throws LdapException
   {
      checkValidUser(computeDefaultOu(user), user);
   }

   String computeDefaultOu(String user)
   {
      if(user == null || user.isEmpty())
         return defaultSearchOu;

      // , is valid char in dn if preceeded by slash
      // TODO clean up with fancy regex
      int cnIndx = user.lastIndexOf("CN");
      if(cnIndx <0)
         return defaultSearchOu;

      int commaIndx = user.indexOf(',', user.lastIndexOf("CN"));
      while (commaIndx > -1)
      {
         if(user.charAt(commaIndx -1) == '\\')
               commaIndx = commaIndx + 1;
         else
         {
            logger.fine("Searching OU for [" + user + "] is [" + user.substring(commaIndx + 1) + "]");
            return user.substring(commaIndx + 1);
         }
         commaIndx = user.indexOf(',', commaIndx);
      }
      return defaultSearchOu;
   }

   @Override
   public void checkValidUser(String ouSearchPrefix, String userDistinguishedName) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            checkValidUser(ouSearchPrefix, userDistinguishedName, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed  validating distinguished name " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   void checkValidUser(String ouSearchPrefix, String userDistinguishedName, LdapConnection boundConnection) throws LdapException
   {
      try//(ClosableCursor c = new ClosableCursor(boundConnection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
      {
         Entry e = boundConnection.lookup(userDistinguishedName);
         if (e == null)
            throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed  validating distinguished name " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }
   
   @Override
   public void checkValidPassword(String userDistinguishedName, String password) throws LdapException
   {
      if(password == null || password.isEmpty())
         throw new LdapException("Failed validating password for distinguished name " + userDistinguishedName + ". Password required.");
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         checkValidPassword(userDistinguishedName, password, connection);
      }
      catch (IOException e)
      {
         throw new LdapException("Failed validating password for distinguished name " + userDistinguishedName, e);
      }
   }
   
   void checkValidPassword(String userDistinguishedName, String password, LdapConnection unboundConnection) throws LdapException
   {
      synchronized (unboundConnection)
      {
         try
         {
            //bind will fail if the user pwd is not valid OR account is disabled
            unboundConnection.bind(userDistinguishedName, password);
            unboundConnection.unBind();
         }
         catch (org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapAuthException("Failed validating password for distinguished name [" + userDistinguishedName + "] " + e.getMessage());
         }
      }
   }

   // other LDAP systems (possible to modify MS to take this attribute
   void changePasswordUserPassword(String userDistinguishedName, String password, LdapConnection boundConnection) throws LdapException
   {
      try 
      {
         modifyAttribute(userDistinguishedName, "userpassword", password, boundConnection);
      }
      catch (Exception e)
      {
         throw new LdapAuthException("Failed changing password for distinguished name [" + userDistinguishedName + "] " + e.getMessage(), e);
      }
   }
   
   @Override
   public void changePasswordUserPassword(String userDistinguishedName, String password) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            changePasswordUserPassword(userDistinguishedName, password, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed  distinguished name " + userDistinguishedName + " change password.", e);
      }
   }

   @Override
   public void changePasswordUnicodePassword(String userDistinguishedName, String password) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            changePasswordUincodePassword(userDistinguishedName, password, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed  distinguished name " + userDistinguishedName + " change password.", e);
      }
   }

   // AD direct
   void changePasswordUincodePassword(String userDistinguishedName, String password, LdapConnection boundConnection) throws LdapException
   {
      try
      {
         byte[] pwdArray = encodeUnicodePassword(password);
         modifyAttribute(userDistinguishedName, "UnicodePwd", pwdArray, boundConnection);
      }
      catch (Exception e)
      {
         throw new LdapAuthException("Failed changing password for distinguished name [" + userDistinguishedName + "] " + e.getMessage());
      }
   }

   @Override
   public List<String> getMemberNamesOfGroup(String userDistinguishedName) throws LdapException
   {
      return getMemberNamesOfGroup(computeDefaultOu(userDistinguishedName), userDistinguishedName);
   }

   @Override
   public List<String> getMemberNamesOfGroup(String ouSearchPrefix, String groupDn) throws LdapException
   {
      List<String> members = new CopyOnWriteArrayList<>();
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            getMemberNamesOfGroupInternal(members, groupDn, connection);
            return new ArrayList<>(members);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed member list lookup for user " + groupDn + " in " + ouSearchPrefix, e);
      }
   }

   void getMemberNamesOfGroupInternal(List<String> members, String groupDn, LdapConnection boundConnection) throws LdapException
   {
      // in ou search prefix, list all distinguished names that have the memberof attribute = to the parameter
      getAttributes(computeDefaultOu(groupDn), groupDn, Collections.singleton("member"), boundConnection).get("member").forEach(member -> {
         if (members.contains(member))
            return;
         members.add(member.toString());
         getMemberNamesOfGroupInternal(members, member.toString(), boundConnection);
      });
   }

   @Override
   public List<String> getGroupNames(String userDistinguishedName) throws LdapException
   {
      return getGroupNames(computeDefaultOu(userDistinguishedName), userDistinguishedName);
   }

   void getGroupsInternal(String userDistinguishedName, Set<String> groups, LdapConnection boundConnection) throws LdapException
   {
      List<String> newGroups = new ArrayList<>();
      // remove CN from string to get top level OU
      String ouSearchPrefix = computeDefaultOu(userDistinguishedName);
//      LdapConnection connection = boundConnection;
      try//(ClosableCursor c = new ClosableCursor(connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
      {
         boundConnection.lookup(userDistinguishedName).forEach(attribute -> {
               //extract all the groups the user is a memberof
               if (attribute.getId().equalsIgnoreCase("memberof"))
                  newGroups.add(String.valueOf(attribute.get()));
//                  System.out.println('['+attribute.getId() +']'+ attribute.get());
            });
      }
      catch(NullPointerException npe)
      {
         throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
      }
      catch (LdapAuthException e)
      {
         throw new LdapAuthException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);      
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
      newGroups.removeAll(groups);
      groups.addAll(newGroups);
      for (String g : newGroups)
      {
         getGroupsInternal(g, groups, boundConnection);
      }
   }

   @Override
   public List<String> getGroupNames(String ouSearchPrefix, String userDistinguishedName) throws LdapException
   {

      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         List<String> groups = getAttributes(computeDefaultOu(userDistinguishedName), userDistinguishedName, Collections.singleton("memberof"), connection).get("memberof").stream()
               .map(String::valueOf)
               .collect(Collectors.toList());
         Set<String> recursiveGroups = new HashSet<>(groups);
         try
         {
            groups.forEach(g -> getGroupsInternal(g, recursiveGroups, connection));
            return new ArrayList<>(recursiveGroups);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public List<String> getGroupNamesAndValidate(String userDistinguishedName, String password) throws LdapException
   {
      return getGroupNamesAndValidate(computeDefaultOu(userDistinguishedName), userDistinguishedName, password);
   }

   @Override
   public List<String> getGroupNamesAndValidate(String ouSearchPrefix, String userDistinguishedName, String password) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         List<String> groups = new ArrayList<>();
         try
         {
            //bind will fail if the user pwd is not valid
            connection.bind(userDistinguishedName, password);

            try//(ClosableCursor c = new ClosableCursor(connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
            {
               connection.lookup(userDistinguishedName).forEach(attribute -> {
                  //extract all the groups the user is a memberof
                  if (attribute.getId().equalsIgnoreCase("memberof"))
                     groups.add(String.valueOf(attribute.get()));
//                  System.out.println('['+attribute.getId() +']'+ attribute.get());
               });
            }
            catch (org.apache.directory.api.ldap.model.exception.LdapException e)
            {
               throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
            }
            catch(NullPointerException npe)
            {
               throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
            }
            finally
            {
               connection.unBind();
            }
            return groups;
         }
         catch (org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapAuthException("Failed validating password for distinguished name " + userDistinguishedName);
         }
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public Map<String, Collection<Object>> getAttributes(String userDistinguishedName, Collection<String> attributeId) throws LdapException
   {
      return getAttributes(computeDefaultOu(userDistinguishedName), userDistinguishedName, attributeId);
   }

   @Override
   public Map<String, Collection<Object>> getAttributes(String ouSearchPrefix, String userDistinguishedName, Collection<String> attributeId) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         try
         {
            connection.bind();
            return getAttributes(ouSearchPrefix, userDistinguishedName, attributeId, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }
   
   Map<String, Collection<Object>> getAttributes(String ouSearchPrefix, String userDistinguishedName, Collection<String> attributeIds, LdapConnection boundConnection) throws LdapException
   {
      Map<String, Collection<Object>> values = new HashMap<>();
//      LdapConnection connection = boundConnection;

      try//(ClosableCursor c = new ClosableCursor(connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
      {
         boundConnection.lookup(userDistinguishedName).getAttributes().forEach(attribute -> {
            //extract all the groups the user is a memberof
            attributeIds.stream().forEach(attributeId -> {
               values.putIfAbsent(attributeId, new ArrayList<>());
               if (attribute.getId().equalsIgnoreCase(attributeId))
               {
                  Collection<Object> valueList = values.get(attributeId);

                  attribute.forEach(v -> {
                     if (v instanceof Value)
                        valueList.add(((Value)v).getValue());
                     else
                        valueList.add(v);
                  });
               }
            }
            );
         });
      }
      catch(NullPointerException npe)
      {
         throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
      }
      catch (LdapAuthException e)
      {
         throw new LdapAuthException("Failed attribute lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed attribute lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
      return values;
   }

   @Override
   public void addAttribute(String userDistinguishedName, String attributeId, Object value) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            addAttribute(userDistinguishedName, attributeId, value, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed " + attributeId + " add for user " + userDistinguishedName, e);
      }
   }
   
   void addAttribute(String userDistinguishedName, String attributeId, Object value, LdapConnection connection) throws LdapException
   {
      try
      {
         Entry entry = connection.lookup(userDistinguishedName);
         if (entry == null)
            throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
         
         ModifyRequest req = new ModifyRequestImpl();
         if (value == null)
            throw new IllegalArgumentException("Value cannot be null.");
         else if (value.getClass().equals(byte[].class))
            req = req.add(attributeId, (byte[])value);
         else if (value instanceof Collection)
         {
            value =((Collection<?>)value).stream().map(v-> String.valueOf(v)).collect(Collectors.toList()).toArray(new String[0]);
            req = req.add(attributeId, (String[])value);
         } 
         else if (value instanceof String[])
         {
            req = req.add(attributeId, (String[])value);
         }
         else
            req = req.add(attributeId, String.valueOf(value));
         Dn dn = entry.getDn();
         req = req.setName(dn);
         ModifyResponse resp = connection.modify(req);
         if (Objects.equals(ResultCodeEnum.SUCCESS, resp.getLdapResult().getResultCode()))
            return;
         throw new LdapException("Failed to add attribute ["+attributeId+"] to user ["+userDistinguishedName+"] " + resp.getLdapResult().getResultCode() + " " + resp.getLdapResult().getDiagnosticMessage());
      }
      catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " add for user " + userDistinguishedName, e);
      }
   }
   
   public void modifyAttribute(String userDistinguishedName, String attributeId, Object value) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            modifyAttribute(userDistinguishedName, attributeId, value, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed " + attributeId + " add for user " + userDistinguishedName, e);
      }      
   }
   
   void modifyAttribute(String userDistinguishedName, String attributeId, Object value, LdapConnection connection) throws LdapException
   {
      if (value == null)
         throw new IllegalArgumentException("Value cannot be null.  Use removeAttribute instead");
      try
      {
         Entry entry = connection.lookup(userDistinguishedName);
         if (entry == null)
            throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
         
         ModifyRequest req = new ModifyRequestImpl();
         if (value.getClass().equals(byte[].class))
            req = req.replace(attributeId, (byte[])value);
         else if (value instanceof Collection)
         {
            value =((Collection<?>)value).stream().map(v-> String.valueOf(v)).collect(Collectors.toList()).toArray(new String[0]);
            req = req.replace(attributeId, (String[])value);
         } 
         else if (value instanceof String[])
         {
            req = req.replace(attributeId, (String[])value);
         }
         else
            req = req.replace(attributeId, String.valueOf(value));
         Dn dn = entry.getDn();
         req = req.setName(dn);
         ModifyResponse resp = connection.modify(req);
         if (Objects.equals(ResultCodeEnum.SUCCESS, resp.getLdapResult().getResultCode()))
            return;
         throw new LdapException("Failed to modify attribute ["+attributeId+"] to user ["+userDistinguishedName+"] " + resp.getLdapResult().getResultCode() + " " + resp.getLdapResult().getDiagnosticMessage());
      }
      catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " modify for user " + userDistinguishedName, e);
      }
   }

   @Override
   public void removeAttribute(String userDistinguishedName, String attributeId, Object value) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            removeAttribute(userDistinguishedName, attributeId, value, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed " + attributeId + " remove for user " + userDistinguishedName, e);
      }
   }
   
   void removeAttribute(String userDistinguishedName, String attributeId, Object value, LdapConnection connection) throws LdapException
   {
      try
      {
         Entry entry = connection.lookup(userDistinguishedName);
         if (entry == null)
            throw new LdapAuthException("No such user [" + userDistinguishedName + "]");

         ModifyRequest req = new ModifyRequestImpl();
         if (value.getClass().equals(byte[].class))
            req = req.remove(attributeId, (byte[])value);
         else
            req = req.remove(attributeId, String.valueOf(value));
         Dn dn = entry.getDn();
         req.setName(dn);
         ModifyResponse resp = connection.modify(req);
         if (Objects.equals(ResultCodeEnum.SUCCESS, resp.getLdapResult().getResultCode()))
            return;
         throw new LdapException("Failed to remove attribute ["+attributeId+"] from user ["+userDistinguishedName+"] " + resp.getLdapResult().getResultCode() + " " + resp.getLdapResult().getDiagnosticMessage());
      }
      catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " remove for user " + userDistinguishedName, e);
      }
   }

   @Override
   public void removeAttribute(String userDistinguishedName, String attributeId) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            removeAttribute(userDistinguishedName, attributeId, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed " + attributeId + " remove for user " + userDistinguishedName, e);
      }
   }
   
   void removeAttribute(String userDistinguishedName, String attributeId, LdapConnection connection) throws LdapException
   {
      try
      {
         Entry entry = connection.lookup(userDistinguishedName);
         if (entry == null)
            throw new LdapAuthException("No such user [" + userDistinguishedName + "]");
         
         ModifyRequest req = new ModifyRequestImpl();
         req = req.remove(attributeId);
         Dn dn = entry.getDn();
         req.setName(dn);
         ModifyResponse resp = connection.modify(req);
         if (Objects.equals(ResultCodeEnum.SUCCESS, resp.getLdapResult().getResultCode()))
            return;
         throw new LdapException("Failed to remove attribute ["+attributeId+"] from user ["+userDistinguishedName+"] " + resp.getLdapResult().getResultCode() + " " + resp.getLdapResult().getDiagnosticMessage());
      }
      catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " remove for user " + userDistinguishedName, e);
      }
   }

   @Override
   public List<String> getMatches(String ouSearchPrefix, String attributeId, String value, boolean caseSensitive) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            return getMatchesInternal(ouSearchPrefix, attributeId, value, caseSensitive, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " lookup for user " + value + " in " + ouSearchPrefix, e);
      }
   }

   List<String> getMatchesInternal(String ouSearchPrefix, String attributeId, String value, boolean caseSensitive, LdapConnection connection) throws LdapException
   {
      if(ouSearchPrefix ==null || ouSearchPrefix.isEmpty())
         ouSearchPrefix = defaultSearchOu;
      try(ClosableCursor c = new ClosableCursor(connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
      {
         EntryCursor cursor = c.cursor;
         List<String> matches = new ArrayList<>();
         cursor.forEach(entry -> {
            entry.getAttributes().forEach(attribute -> {
               //extract all the groups the user is a memberof
               if (attribute.getId().equalsIgnoreCase(attributeId))
                  attribute.forEach(v -> {
                     Object val;
                     if (v instanceof Value)
                        val = (((Value)v).getValue());
                     else
                        val = v;
                     if(!caseSensitive && val instanceof String)
                     {
                        if(((String)val).equalsIgnoreCase(value))
                           matches .add(String.valueOf(entry.get("distinguishedName").get()));
                     }else
                     {
                        if(Objects.equals(value, val))
                           matches .add(String.valueOf(entry.get("distinguishedName").get()));
                     }
                  });
            });
         });
         return matches;
      }
      catch (LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed " + attributeId + " lookup for value " + value + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public List<String> getMatches(String ouSearchPrefix, String attributeId, byte[] value) throws LdapException
   {
      try (LdapNetworkConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            return getMatchesInternal(ouSearchPrefix, attributeId, value, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " lookup for user " + value + " in " + ouSearchPrefix, e);
      }
   }
   
   List<String> getMatchesInternal (String ouSearchPrefix, String attributeId, byte[] value, LdapConnection boundConnection) throws LdapException
   {
      if(ouSearchPrefix ==null || ouSearchPrefix.isEmpty())
         ouSearchPrefix = defaultSearchOu;
      try(ClosableCursor c = new ClosableCursor(boundConnection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*")))
      {
         EntryCursor cursor = c.cursor;
         List<String> matches = new ArrayList<>();
         cursor.forEach(entry -> {
            if (entry.contains(attributeId, value))
            {
               matches.add(String.valueOf(entry.get("distinguishedName").get()));
            }
         });
         return matches;
      }catch(LdapException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new LdapException("Failed " + attributeId + " lookup for value " + value + " in " + ouSearchPrefix, e);
      }
   }
//
//   private Entry getEntryFor(String distinguishedName, EntryCursor cursor) throws LdapException
//   {
//      return StreamSupport.stream(cursor.spliterator(), false)
//         .filter(entry -> entry.contains("distinguishedName", distinguishedName))
//         .findAny()
//         .orElseThrow(() -> new LdapAuthException(distinguishedName + "not found."));
//   }

   @Override
   public boolean isMemberOf(String groupDn, String userDn) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         try
         {
            connection.bind();
            return isMemberOf(groupDn, userDn, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed isMemberOf [" + userDn + "] of group [" + groupDn + "]", e);
      }
   }

   boolean isMemberOf(String groupDn, String userDn, LdapConnection boundConnection) throws LdapException
   {
      try
      {
         boolean found = getAttributes(computeDefaultOu(groupDn), groupDn, Collections.singleton("member"), boundConnection).get("member").stream()
            .anyMatch(member -> member.toString().equals(userDn) || isMemberOf(member.toString(), userDn));
         return found;
      }
      catch (LdapAuthException ae)
      {
         return false;
      }
   }

   public void createUser(String cn, String ou, String unicodePassword, String userPassword, List<String> objectClasses, 
		   String instanceType, String objectCategory, Map<String, String> attributes, LdapConnection boundConnection) throws LdapException
   {
		String dn = "CN=" + cn + ",OU=" + ou;
		try {
			Entry entry = new DefaultEntry();
			entry.add("cn", cn);
			for (String c: objectClasses)
				entry.add("objectClass", c);
//			entry.add("objectClass", "organizationalPerson");
//			entry.add("objectClass", "person");
//			entry.add("objectClass", "top");
//			entry.add("objectClass", "user");
			entry.add("instanceType", instanceType);
//			entry.add("instanceType", "4");
			entry.add("objectCategory",objectCategory);
//			entry.add("objectCategory",
//					"CN=Person,CN=Schema,CN=Configuration,CN={DC42C6A0-6A5A-4683-9B9C-E7B7C93E30E9}");
			
			for(java.util.Map.Entry<String,String> e : attributes.entrySet())
				entry.add(e.getKey(), e.getValue());
//			entry.add("distinguishedName", dn);
//			entry.add("msDS-UserAccountDisabled", "FALSE");
//			entry.add("msDS-UserDontExpirePassword", "TRUE");
//			entry.add("name", displayName);
//			entry.add("sAMAccountName", userName);

			entry.setDn(new Dn(dn));
//			entry.setDn(dn);
			if(unicodePassword != null && !unicodePassword.isEmpty())
			{
	            byte[] pwdArray = encodeUnicodePassword(unicodePassword);
                entry.add("UnicodePwd", pwdArray);
			}
			if(userPassword != null && !userPassword.isEmpty())
			{
                entry.add("userpassword", userPassword);
			}
			AddRequest addRequest = new AddRequestImpl();
			addRequest.setEntry(entry);

			AddResponse response = boundConnection.add(addRequest);

			if (null == response)
				throw new LdapException("Null response for ldap entry add of [" + dn + "]");
			if (!ResultCodeEnum.SUCCESS.equals(response.getLdapResult().getResultCode()))
				throw new LdapException(
						"Response " + response.getLdapResult().getResultCode() + " for ldap entry add of [" + dn + "]\n"+response.getLdapResult().getDiagnosticMessage());
		} catch (org.apache.directory.api.ldap.model.exception.LdapException e) {
			throw new LdapException("Failed add entry [" + dn + "]", e);
		}
	}

private byte[] encodeUnicodePassword(String password) {
	String quotedPassword = "\"" + password + "\"";
	char unicodePwd[] = quotedPassword.toCharArray();
	byte pwdArray[] = new byte[unicodePwd.length * 2];
	for (int i = 0; i < unicodePwd.length; i++)
	{
	    pwdArray[i * 2 + 1] = (byte)(unicodePwd[i] >>> 8);
	    pwdArray[i * 2 + 0] = (byte)(unicodePwd[i] & 0xff);
	}
	return pwdArray;
}
   
   public void createUser(String cn, String ou, String unicodePassword, String userPassword, List<String> objectClasses, 
		   String instanceType, String objectCategory, Map<String, String> attributes) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         try
         {
            connection.bind();
            createUser(cn, ou, unicodePassword, userPassword, objectClasses, instanceType, objectCategory, attributes, connection);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed add entry [CN=" + cn + ",OU=" + ou + "]", e);
      }
   }

	public void addUserToGroup(String userDn, String groupDn, LdapConnection boundConnection) throws LdapException {
		try {

			addAttribute(groupDn, "member", userDn, boundConnection);
			//addAttribute(userDn, "memberOf", groupDn, boundConnection);

		} catch (LdapException e) {
			throw new LdapException("Failed add user [" + userDn + "] to group [" + groupDn + "]", e);
		}
	}

	public void removeUserFromGroup(String userDn, String groupDn, LdapConnection boundConnection)
			throws LdapException {
		try {

			removeAttribute(groupDn, "member", userDn, boundConnection);
			//removeAttribute(userDn, "memberOf", groupDn, boundConnection);

		} catch (LdapException e) {
			throw new LdapException("Failed remove user [" + userDn + "] from group [" + groupDn + "]", e);
		}
	}

	@Override
	public void addUserToGroup(String userDn, String groupDn) throws LdapException {
		try (LdapConnection connection = new LdapNetworkConnection(config)) {
			try {
				connection.bind();
				addUserToGroup(userDn, groupDn, connection);
			} finally {
				connection.unBind();
			}
		} catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e) {
			throw new LdapException("Failed add user [" + userDn + "] to group [" + groupDn + "]", e);
		}
	}

	@Override
	public void removeUserFromGroup(String userDn, String groupDn) throws LdapException {
		try (LdapConnection connection = new LdapNetworkConnection(config)) {
			try {
				connection.bind();
				removeUserFromGroup(userDn, groupDn, connection);
			} finally {
				connection.unBind();
			}
		} catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e) {
			throw new LdapException("Failed remove user [" + userDn + "] from group [" + groupDn + "]", e);
		}
	}


static class ClosableCursor implements AutoCloseable
   {
      final EntryCursor cursor;

      public ClosableCursor(EntryCursor cursor)
      {
         this.cursor = cursor;
      }

      @Override
      public void close() throws Exception
      {
         cursor.close();
      }
   }
}
