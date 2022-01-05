#!/bin/bash

if [ -f "./logs/brte2.log" ]; then
  rm -rf ./logs/brte2.log
  echo "LogFile brte2.log is deleted"
else
  echo "LogFile brte2.log is not exists"
fi