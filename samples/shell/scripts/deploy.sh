#!/bin/bash
# Deliberately buggy deploy script used to demo the ShellCheck rules.
TARGET=$1
cd $TARGET
for f in $(ls *.tar.gz); do
  tar xzf $f
done
rm -rf $OUT/*
if [ $ENV == "prod" ]; then
  echo "Deploying to `hostname`"
fi
