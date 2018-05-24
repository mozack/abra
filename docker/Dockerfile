FROM centos
LABEL MAINTAINER "Lisle Mose"

RUN yum -y install \
     gcc-c++ \ 
     git \
     make \
     maven 

ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0/

RUN git clone https://github.com/mozack/abra.git && \
       cd abra && \
       make
       
ENTRYPOINT ["/usr/bin/java", "-Xmx5G", "-jar", "/abra/target/abra-0.97-SNAPSHOT-jar-with-dependencies.jar"]
