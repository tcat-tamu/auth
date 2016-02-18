package edu.tamu.tcat.account.apacheds.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.directory.api.ldap.codec.api.LdapApiService;
import org.apache.directory.api.ldap.codec.api.LdapApiServiceFactory;
import org.apache.directory.api.ldap.codec.protocol.mina.LdapProtocolCodecFactory;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.message.SearchScope;
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
   private LdapConnectionConfig config = null;
   private String defaultSearchOu;

   /**
    * must be called after configure and before any queries
    */
   public void init()
   {
      if(config == null)
         throw new IllegalStateException("Configure must be called before init");
      LdapApiService s = LdapApiServiceFactory.getSingleton();
      if (s.getProtocolCodecFactory() == null)
         s.registerProtocolCodecFactory(new LdapProtocolCodecFactory());
   }

   /** must be called before init */
   public void configure(String ip, int port, String userDn, String userPassword, boolean useSsl, String defaultSearchOu)
   {
      config = new LdapConnectionConfig();
      config.setLdapHost(ip);
      config.setLdapPort(port);
      config.setName(userDn);
      config.setCredentials(userPassword);
      config.setUseSsl(useSsl);
      this.defaultSearchOu = defaultSearchOu;
   }

   // this may be replaced with getMatches
   protected String findDistinguishingName(String ouSearchPrefix, String otherName) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         AtomicReference<String> found = new AtomicReference<>(null);
         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            cursor.forEach(entry -> {
               //change this to 
               if (entry.contains("sAMAccountName", otherName) || entry.contains("userPrincipleName", otherName))
               {
                  found.set(String.valueOf(entry.get("distinguishedName").get()));
               }
            });
         }
         finally
         {
            connection.unBind();
         }
         if (found.get() != null)
            return found.get();
         throw new LdapException("No such user " + otherName + " in " + ouSearchPrefix);
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed finding distinguished name " + otherName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public void checkValidUser(String user) throws LdapException
   {
      checkValidUser(computeDefaultOu(user), user);
   }

   protected String computeDefaultOu(String user)
   {
      return defaultSearchOu == null || defaultSearchOu.isEmpty() ? user.substring(user.indexOf(',') + 1) : defaultSearchOu;
   }

   @Override
   public void checkValidUser(String ouSearchPrefix, String userDistinguishedName) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();
         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            try
            {
               getEntryFor(userDistinguishedName, cursor);
            }
            catch (LdapException e)
            {
               throw new LdapException("No such user " + userDistinguishedName + " in " + ouSearchPrefix);
            }
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

   @Override
   public void checkValidPassword(String userDistinguishedName, String password) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         try
         {
            //bind will fail if the user pwd is not valid
            connection.bind(userDistinguishedName, password);
            connection.unBind();
         }
         catch (org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapAuthException("Failed validating password for distinguished name " + userDistinguishedName);
         }
      }
      catch (IOException e)
      {
         throw new LdapException("Failed validating password for distinguished name " + userDistinguishedName, e);
      }

   }

   @Override
   public List<String> getMembersOfGroup(String userDistinguishedName) throws LdapException
   {
      return getMembersOfGroup(computeDefaultOu(userDistinguishedName), userDistinguishedName);
   }
   
   @Override
   public List<String> getMembersOfGroup(String ouSearchPrefix, String distinguishedName) throws LdapException
   {
      List<String> members = new ArrayList<>();
      // in ou search prefix, list all distinguished names that have the memberof attribute = to the parameter
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {

         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            
            cursor.forEach(entry -> {
               if (entry.contains("memberof", distinguishedName))
               {
                  members.add(String.valueOf(entry.get("distinguishedName").get()));
               }
            });
         }
         catch (org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed member list lookup for group " + distinguishedName + " in " + ouSearchPrefix);
         }
         finally
         {
            connection.unBind();
         }
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed member list lookup for group " + distinguishedName + " in " + ouSearchPrefix, e);
      }
      return members;
   }

   @Override
   public List<String> getGroups(String userDistinguishedName) throws LdapException
   {
      return getGroups(computeDefaultOu(userDistinguishedName), userDistinguishedName);
   }
   
   private void getGroupsInternal(String userDistinguishedName, Set<String> groups) throws LdapException
   {
      List<String> newGroups = new ArrayList<>();
      // remove CN from string to get top level OU
      String ouSearchPrefix = userDistinguishedName.substring(userDistinguishedName.indexOf(',') +1);
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.ONELEVEL, "*");
            getEntryFor(userDistinguishedName, cursor).forEach(attribute -> {
               //extract all the groups the user is a memberof
               if (attribute.getId().equalsIgnoreCase("memberof"))
                  newGroups.add(String.valueOf(attribute.get()));
//                  System.out.println('['+attribute.getId() +']'+ attribute.get());
            });
         }
         catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix);
         }
         finally
         {
            connection.unBind();
         }
         newGroups.removeAll(groups);
         groups.addAll(newGroups);
         for (String g : newGroups)
         {
            getGroupsInternal(g, groups);
         }

         return;
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public List<String> getGroups(String ouSearchPrefix, String userDistinguishedName) throws LdapException
   {
      List<String> groups = new ArrayList<>();
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            getEntryFor(userDistinguishedName, cursor).forEach(attribute -> {
               //extract all the groups the user is a memberof
               if (attribute.getId().equalsIgnoreCase("memberof"))
                  groups.add(String.valueOf(attribute.get()));
//                  System.out.println('['+attribute.getId() +']'+ attribute.get());
            });
         }
         catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix);
         }
         finally
         {
            connection.unBind();
         }
         Set<String> recursiveGroups = new HashSet<>(groups);
         for (String g : groups)
         {
            getGroupsInternal(g, recursiveGroups);
         }
         return new ArrayList<>(recursiveGroups);
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public List<String> getGroupsAndValidate(String userDistinguishedName, String password) throws LdapException
   {
      return getGroupsAndValidate(computeDefaultOu(userDistinguishedName), userDistinguishedName, password);
   }

   @Override
   public List<String> getGroupsAndValidate(String ouSearchPrefix, String userDistinguishedName, String password) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         List<String> groups = new ArrayList<>();
         try
         {

            //bind will fail if the user pwd is not valid
            connection.bind(userDistinguishedName, password);

            try
            {
               EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
               getEntryFor(userDistinguishedName, cursor).forEach(attribute -> {
                  //extract all the groups the user is a memberof
                  if (attribute.getId().equalsIgnoreCase("memberof"))
                     groups.add(String.valueOf(attribute.get()));
//                  System.out.println('['+attribute.getId() +']'+ attribute.get());
               });
            }
            catch (org.apache.directory.api.ldap.model.exception.LdapException e)
            {
               throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix);
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
      catch (IOException e)
      {
         throw new LdapException("Failed group list lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public Collection<Object> getAttributes(String userDistinguishedName, String attributeId) throws LdapException
   {
      return getAttributes(computeDefaultOu(userDistinguishedName), userDistinguishedName, attributeId);
   }

   @Override
   public Collection<Object> getAttributes(String ouSearchPrefix, String userDistinguishedName, String attributeId) throws LdapException
   {
      List<Object> values = new ArrayList<>();
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            getEntryFor(userDistinguishedName, cursor).forEach(attribute -> {
               //extract all the groups the user is a memberof
               if (attribute.getId().equalsIgnoreCase(attributeId))
                  values.add(attribute.get());
//                  System.out.println('['+attribute.getId() +']'+ attribute.get());
            });
         }
         catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed " + attributeId + " lookup for user " + userDistinguishedName + " in " + ouSearchPrefix);
         }
         finally
         {
            connection.unBind();
         }
         return values;
      }
      catch (IOException | org.apache.directory.api.ldap.model.exception.LdapException e)
      {
         throw new LdapException("Failed " + attributeId + " lookup for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
      }
   }

   @Override
   public void addAttribute(String ouSearchPrefix, String userDistinguishedName, String attributeId, Object value) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            Entry entry;
            if (value.getClass().equals(byte[].class))
               entry = getEntryFor(userDistinguishedName, cursor).add(attributeId, (byte[])value);
            else
               entry = getEntryFor(userDistinguishedName, cursor).add(attributeId, String.valueOf(value));
            connection.modify(entry, ModificationOperation.ADD_ATTRIBUTE);
         }
         catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed " + attributeId + " add for user " + userDistinguishedName + " in " + ouSearchPrefix, e);
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

   @Override
   public void removeAttribute(String ouSearchPrefix, String userDistinguishedName, String attributeId, Object value) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            Entry entry = getEntryFor(userDistinguishedName, cursor);
            if (value.getClass().equals(byte[].class))
               if (!entry.remove(attributeId, (byte[])value))
                  return;
               else if (!entry.remove(attributeId, String.valueOf(value)))
                  return;

            connection.modify(entry, ModificationOperation.REMOVE_ATTRIBUTE);
         }
         catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed " + attributeId + " remove for user " + userDistinguishedName + " in " + ouSearchPrefix);
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

   @Override
   public void removeAttribute(String ouSearchPrefix, String userDistinguishedName, String attributeId) throws LdapException
   {
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");

            Entry entry = getEntryFor(userDistinguishedName, cursor);
            entry.removeAttributes(attributeId);
            connection.modify(entry, ModificationOperation.REMOVE_ATTRIBUTE);
         }
         catch (LdapException | org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed " + attributeId + " lookup for user " + userDistinguishedName + " in " + ouSearchPrefix);
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

   @Override
   public List<String> getMatches(String ouSearchPrefix, String attributeId, String value) throws LdapException
   {
      if(ouSearchPrefix ==null || ouSearchPrefix.isEmpty())
         ouSearchPrefix = defaultSearchOu;
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            List<String> matches = new ArrayList<>();
            cursor.forEach(entry -> {
               if (entry.contains(attributeId, value))
               {
                  matches .add(String.valueOf(entry.get("distinguishedName")));
               }
            });
            return matches;
         }
         catch (org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed " + attributeId +" lookup for value " + value + " in " + ouSearchPrefix);
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

   @Override
   public List<String> getMatches(String ouSearchPrefix, String attributeId, byte[] value) throws LdapException
   {
      if(ouSearchPrefix ==null || ouSearchPrefix.isEmpty())
         ouSearchPrefix = defaultSearchOu;
      try (LdapConnection connection = new LdapNetworkConnection(config))
      {
         connection.bind();

         try
         {
            EntryCursor cursor = connection.search(ouSearchPrefix, "(objectclass=*)", SearchScope.SUBTREE, "*");
            List<String> matches = new ArrayList<>();
            cursor.forEach(entry -> {
               if (entry.contains(attributeId, value))
               {
                  matches .add(String.valueOf(entry.get("distinguishedName")));
               }
            });
            return matches;
         }
         catch (org.apache.directory.api.ldap.model.exception.LdapException e)
         {
            throw new LdapException("Failed " + attributeId +" lookup for value " + value + " in " + ouSearchPrefix);
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
   
   private Entry getEntryFor(String distinguishedName, EntryCursor cursor) throws LdapException
   {

      AtomicReference<Entry> found = new AtomicReference<Entry>(null);
      cursor.forEach(entry -> {
         if (entry.contains("distinguishedName", distinguishedName))
         {
            found.set(entry);
         }
      });
      if (found.get() != null)
         return found.get();
      throw new LdapException(distinguishedName + "not found.");
   }
}
