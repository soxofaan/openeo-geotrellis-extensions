package be.vito.eodata.geopysparkextensions

import java.net.URI
import java.security.PrivilegedAction

import geotrellis.spark.io.accumulo.AccumuloInstance
import org.apache.accumulo.core.client.ClientConfiguration
import org.apache.accumulo.core.client.security.tokens._
import org.apache.hadoop.security.UserGroupInformation

object KerberizedAccumuloInstance {

  def apply(uri: URI): AccumuloInstance = {
    import geotrellis.util.UriUtils._

    val zookeeper = uri.getHost
    val instance = uri.getPath.drop(1)
    val (user, pass) = getUserInfo(uri)
    val useKerberos = ClientConfiguration
      .loadDefault()
      .getBoolean(ClientConfiguration.ClientProperty.INSTANCE_RPC_SASL_ENABLED.getKey, false)

    val (username: String, token: AuthenticationToken) = {
      if (useKerberos) {
        if (UserGroupInformation.getCurrentUser.hasKerberosCredentials) {
          val token = new KerberosToken()
          (user.getOrElse(token.getPrincipal()), token)
        } else if (UserGroupInformation.getLoginUser.hasKerberosCredentials) {

          UserGroupInformation.getLoginUser.doAs[(String, AuthenticationToken)](new PrivilegedAction[(String, AuthenticationToken)] {
            override def run(): (String, AuthenticationToken) = {
              val token = new KerberosToken()
              import org.apache.accumulo.core.client.admin.DelegationTokenConfig

              val accumulo = AccumuloInstance(
                instance, zookeeper,
                user.getOrElse(token.getPrincipal()),
                token)
              val delegationToken = accumulo.connector.securityOperations.getDelegationToken(new DelegationTokenConfig)
              //it would probably still be better to create the delegation token on spark-submit, distribute it using hdfs
              // and then to read it here
              // look at setConnectorInfo:
              // https://github.com/apache/accumulo/blob/master/core/src/main/java/org/apache/accumulo/core/client/mapred/AbstractInputFormat.java
              // geomesa also had similar issue

              return (user.getOrElse(token.getPrincipal()), delegationToken)

            }
          })

        } else {
          throw new RuntimeException("No Kerberos credentials to log in to Accumulo found, please log in first.")
        }

      } else {
        (user.getOrElse("root"), new PasswordToken(pass.getOrElse("")))
      }
    }

    AccumuloInstance(instance, zookeeper, username, token)
  }
}
