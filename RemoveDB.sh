#!/bin/bash

if [ -d "./out" ] && [ -d "./output-directory" ]; then
  rm -rf ./out && rm -rf ./output-directory
  echo "Directory ./out and ./output-directory is deleted"
else
  echo "Directory ./out is not exists"
fi