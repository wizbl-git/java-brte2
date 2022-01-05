FROM brte2protocol/brte2-gradle

RUN set -o errexit -o nounset \
    && echo "git clone" \
    && git clone https://github.com/wizbl-git/java-brte2.git \
    && cd java-brte2 \
    && gradle build

WORKDIR /java-brte2

EXPOSE 18888