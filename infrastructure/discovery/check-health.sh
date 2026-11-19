#!/usr/bin/env bash
set -eu
exec 3<>"/dev/tcp/127.0.0.1/$1"
printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
read -r protocol status message <&3
[[ "$status" == 200 ]]
