#!/usr/bin/env bash

set -e

while [[ $# -gt 0 ]]; do
  case $1 in
    --spark-home)
      spark_home="$2"
      shift
      shift
      ;;
    --spark-distributive)
      spark_distr="$2"
      shift
      shift
      ;;
    --enable-livy)
      enable_livy=1
      shift
      ;;
    *)
      echo "Unknown argument $1"
      exit 1
      ;;
  esac
done

if [[ -z $spark_home ]]; then
  echo "Parameter --spark-home should be set"
  exit 1
fi

if [[ -z $spark_distr ]]; then
  echo "Parameter --spark-distributive should be set"
  exit 1
fi

spyt_home=$(realpath "$spark_home/spyt-package")

mkdir -p $spark_home
tar --warning=no-unknown-keyword -xf "$spark_distr" -C "$spark_home"
mv "$spark_home/${spark_distr:0:-4}" "$spark_home/spark"

if [ -f spyt-package.zip ]; then
  unzip -o spyt-package.zip -d "$spark_home"
  javaagent_opt="-javaagent:$(ls $spyt_home/jars/*spark-yt-spark-patch*)"
  echo "$javaagent_opt" > $spyt_home/conf/java-opts
fi

if [ $enable_livy ]; then
  tar --warning=no-unknown-keyword -xf livy.tgz -C $spark_home
fi

for file in $(ls); do
  if [[ $file =~ ^(.*)-arc-dep(.*)$ ]]; then
    target=${BASH_REMATCH[1]}
    extension=${BASH_REMATCH[2]}
    if [ $extension = ".zip" ] || [ $extension == ".jar" ]; then
      unzip $file -d $target
    else
      mkdir $target && tar -xf $file -C $target
    fi
  fi
done

mkdir -p ${SPARK_LOCAL_DIRS:-/tmp} && echo "$YT_OPERATION_ID" > ${SPARK_LOCAL_DIRS:-/tmp}/operation_id
