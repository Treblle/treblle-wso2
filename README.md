# Treblle Data Publisher

## Overview

The Treblle Data Publisher extension retrieves and processes data from the [Global Synapse Handler](https://ei.docs.wso2.com/en/latest/micro-integrator/develop/customizations/creating-synapse-handlers/) in the WSO2 API Gateway. It asynchronously sends this data to Treblle for logging and monitoring. Data is queued and sent by a worker thread, with one retry allowed if the transmission fails.

This repository contains **two versions** of the extension, each with slight variations in configuration. Please refer to the specific README for the version you are interested in.
