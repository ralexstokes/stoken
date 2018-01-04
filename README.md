# stoken

A proof-of-work blockchain that maintains a ledger of coin balances like Bitcoin.

This repository contains the software to run a `stoken` node which runs the proof-of-work (PoW) algorithm, participates in a peer-to-peer gossip protocol and offers RPC endpoints to inspect the node's state.

## About

The node software is organized as a series of components that primarily communicate via a system `queue`. A `scheduler` runs a series of workers that process messages on the `queue`. The `p2p` and `rpc` components can submit work to the queue in response to external events.

The node state contains a transaction pool of outstanding transactions submitted to the network, a ledger reflecting a summary of all transaction history maintained on the chain and a collection of all blocks submitted to the network organized as the central chain data structure.

## Development

This software uses the [Component framework](https://github.com/stuartsierra/component) to take advantage of the [reloaded](https://github.com/stuartsierra/reloaded) workflow. See [http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded) for details.

To begin an instance of the development system described in `dev/dev.clj`, open a REPL and call `(reset)`.

## Copyright and License

Copyright © 2017 ralexstokes

TODO: [Choose a license](http://choosealicense.com/)
