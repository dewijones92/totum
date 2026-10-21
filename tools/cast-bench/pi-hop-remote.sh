set -u
PORT=18765
del() { for h in $(sudo nft -a list chain ip filter INPUT | grep dewi-temp-cast-bench | grep -o 'handle [0-9]*' | cut -d' ' -f2); do sudo nft delete rule ip filter INPUT handle "$h" && echo "dewidebug pihop deleted rule handle $h"; done; }
del
sudo nft add rule ip filter INPUT position 5 iifname eth0 ip saddr '{ 192.168.0.219, 192.168.0.13 }' tcp dport $PORT counter accept comment '"dewi-temp-cast-bench"'
echo "dewidebug pihop rule: $(sudo nft -a list chain ip filter INPUT | grep dewi-temp-cast-bench)"
sudo systemd-run --quiet --on-active=6h --unit=dewi-cast-bench-backstop-$(date +%s) /bin/sh -c "for h in \$(nft -a list chain ip filter INPUT | grep dewi-temp-cast-bench | grep -o 'handle [0-9]*' | cut -d' ' -f2); do nft delete rule ip filter INPUT handle \$h; done" && echo "dewidebug pihop backstop armed (6h)"
trap 'del; exit' HUP INT TERM PIPE EXIT
while true; do sleep 60; echo "dewidebug pihop alive $(date +%T) $(sudo nft list chain ip filter INPUT | grep dewi-temp-cast-bench | grep -o 'packets [0-9]*')"; done
