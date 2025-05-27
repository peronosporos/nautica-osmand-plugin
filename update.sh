#!/bin/bash
git add .
git commit -m "${1:-Update Nautica plugin}"
git push origin main
