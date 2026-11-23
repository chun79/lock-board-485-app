# Source before building on this machine: points Gradle at the shared Android toolchain cache.
# Paths are machine-specific; override any variable before sourcing if your layout differs.
: "${LB_TOOLCHAIN:=$HOME/.cache/agj36-android-sdk}"
export JAVA_HOME="${LB_JAVA_HOME:-$LB_TOOLCHAIN/jdk-root/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$LB_TOOLCHAIN/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$LB_TOOLCHAIN/gradle-home}"
export PATH="$JAVA_HOME/bin:$PATH"
# The unpacked JDK 17 ships a PKCS12 cacerts without trusted-cert attributes (empty trust anchors);
# use the OS-maintained Java truststore so Gradle can download dependencies.
if [ -f /etc/ssl/certs/java/cacerts ]; then
  export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi
