package edu.tamu.tcat.account.apacheds;

import java.util.Collection;
import java.util.List;

/**
 * This interface provides read only access to an LDAP server
 * */
public interface LdapHelperReader
{
   /**
    * verify that this user is a valid distinguished name on this LDAP server
    * @throws LdapException if an error occurs
    * @throws LdapAuthException if the user is not valid
    * */
   void checkValidUser(String user) throws LdapException, LdapAuthException;

   /**
    * verify that this user is a valid distinguished name on this LDAP server in the specified OU
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName
    * @throws LdapException if an error occurs
    * @throws LdapAuthException if the user is not valid
    * */
   void checkValidUser(String ouSearchPrefix, String userDistinguishedName) throws LdapException, LdapAuthException;

   /**
    * verify that this user is a valid distinguished name on this LDAP server in the specified OU
    * @throws LdapException if an error occurs
    * @throws LdapAuthException if the user is not valid or the password is not valid
    * */
   void checkValidPassword(String userDistinguishedName, String password) throws LdapException, LdapAuthException;

   /***
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName
    * @return return list of distinguished names for entries that match the attribute value pair*/
   List<String> getMatches(String ouSearchPrefix, String attribute, String value) throws LdapException;
   
   /**
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName
    * *@return return list of distinguished names for entries that match the attribute value pair*/
   List<String> getMatches(String ouSearchPrefix, String attribute, byte[] value) throws LdapException;

   /**
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName
    * *@return return list of distinguished names for entries that are members of the group specified by groupDistinguishedName in the specified ou*/
   List<String> getMemberNamesOfGroup(String ouSearchPrefix, String groupDistinguishedName) throws LdapException;

   /***@return return list of distinguished names for entries that are members of the group specified by groupDistinguishedName */
   List<String> getMemberNamesOfGroup(String groupDistinguishedName) throws LdapException;
   
   /***This is a recursive search
    * @return return list of distinguished names for the groups for the distinguished name */
   List<String> getGroupNames(String userDistinguishedName) throws LdapException;

   /***This is a recursive search
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName    
    * @return return list of distinguished names for the groups for the distinguished name starting in specified ou*/
   List<String> getGroupNames(String ouSearchPrefix, String userDistinguishedName) throws LdapException, LdapAuthException;

   /***This is a recursive search
    * @throws LdapAuthException if the user is not valid or the password is not valid
    * @return return list of distinguished names for the groups for the distinguished name */
   List<String> getGroupNamesAndValidate(String userDistinguishedName, String password) throws LdapException, LdapAuthException;

   /***This is a recursive search
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName
    * @throws LdapAuthException if the user is not valid or the password is not valid
    * @return return list of distinguished names for the groups for the distinguished name starting in specified ou*/
   List<String> getGroupNamesAndValidate(String ouSearchPrefix, String userDistinguishedName, String password) throws LdapException;

   /**
    * Expected return types are String and byte[]
    * @return return a collection of Object that correspond to the values stored for the specified attribute or an empty collection if no values for the specified attribute are present
    * 
    * */
   Collection<Object> getAttributes(String userDistinguishedName, String attributeId) throws LdapException;

   /**
    * Expected return types are String and byte[]
    * @ param ouSearchPrefix if null ou search prefix will be extracted from userDistinguishedName
    * @return return a collection of Object that correspond to the values stored for the specified attribute or an empty collection if no values for the specified attribute are present
    * 
    * */
   Collection<Object> getAttributes(String ouSearchPrefix, String userDistinguishedName, String attributeId) throws LdapException;
}