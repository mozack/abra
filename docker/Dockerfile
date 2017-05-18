FROM centos
LABEL MAINTAINER "Lisle Mose"

RUN yum -y install git make maven gcc-c++

ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.131-2.b11.el7_3.x86_64/

RUN git clone https://github.com/mozack/abra.git && \
       cd abra && \
       make
ENTRYPOINT ["/usr/bin/java", "-Xmx5G", "-jar", "/abra/target/abra-0.97-SNAPSHOT-jar-with-dependencies.jar"]
