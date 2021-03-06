package edu.tamu.tcat.account.jaxrs.provider.signature;

import java.io.InputStream;

import edu.tamu.tcat.account.signature.SignatureException;

/**
 * Base interface for stream based signature verifiers.
 */
public interface SignatureStreamVerifier
{
   /**
    * @return An {@link InputStream} from which to read so that the verifier sees all data proceeding through the stream.
    */
   InputStream getProxyStream();
   
   /**
    * Interface for stream based signature verifier which is not initialized with the payload.
    * @param <PayloadType> The type of payload used by the verifier
    */
   interface SignatureStreamDelayedPublicKeyVerifier<PayloadType> extends SignatureStreamVerifier
   {
      /**
       * Check the signature through the stream is valid or throw if not.
       * @param payload The payload which is used by the underlying verifier.
       */
      void checkSignature(PayloadType payload);
   }
   
   /**
    * Interface for stream based signature verifier which is initialized with the payload.
    */
   interface SignatureStreamWithPublicKeyVerifier extends SignatureStreamVerifier
   {
      /**
       * Check the signature through the stream is valid or throw if not.
       * @throws SignatureException Thrown if the signature cannot be checked.  Should not be thrown if the signature is simply not valid for the content.
       */
      void checkSignature() throws SignatureException;
   }
}
