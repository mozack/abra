FROM  alpine
LABEL MAINTAINER "Lisle Mose"

RUN apk -U add \
     g++ \ 
     git \
     make \
     maven \
     openjdk8

RUN git clone https://github.com/mozack/abra.git && \
       cd abra && \
       make
       
ENTRYPOINT ["/usr/bin/java", "-Xmx5G", "-jar", "/abra/target/abra-0.97-SNAPSHOT-jar-with-dependencies.jar"]

