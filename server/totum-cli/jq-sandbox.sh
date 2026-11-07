#!/bin/sh
for arg in "$@"; do
  case "$arg" in
    /*|~*|../*|*/../*|*/..|..|-L*|--library-path*|*import[[:space:]'"']*|*include[[:space:]'"']*)
      echo "jq-sandbox: refused \"$arg\": jq here reads only the files in its own folder" >&2
      exit 2
      ;;
  esac
done
exec env -i /usr/bin/jq "$@"
