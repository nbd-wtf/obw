<a href="https://nbd.wtf"><img align="right" height="196" src="https://user-images.githubusercontent.com/1653275/194609043-0add674b-dd40-41ed-986c-ab4a2e053092.png" alt=" NBD logo > Open Bitcoin Wallet (OBW)"/></a>

# OBW - The _Open Bitcoin Wallet_

The _Open Bitcoin Wallet_ (OBW) is an Android Bitcoin and Lightning wallet, a fork of [Simple Bitcoin Wallet](https://github.com/btcontract/wallet) focused on providing a pleasant and simple experience with advanced features.

## How to install OBW

To install the _Open Bitcoin Wallet_ you have 3 options right now:

1. Grab an APK from the [releases](https://github.com/nbd-wtf/obw/releases) page
2. Download from [Google Play](https://play.google.com/store/apps/details?id=wtf.nbd.obw)
3. Build from source (see [instructions below](#how-to-build-obw-from-source))

## OBW features

Some of the features OBW includes are:

  - Lightweight Bitcoin wallet that uses Electrum servers
    - can use an Electrum server specified by the user or random ones
    - support for RBF and CPFP
    - coin control (select which UTXOs to spend or not)
    - payments to multiple addresses
  - Standalone, autonomous Lightning capabilities:
    - can connect to any node
    - open channels, close channels, force-close channels, close channels to specific address
    - route payments without the use of any third-party service, all routing done on the wallet
    - offer to retry payments with increased fee tolerance if the first attempts fail
  - Private by default
    - when connecting to Lightning peers, uses a different node identity for each peer
    - uses a random node identity for each invoice
    - full TOR support: built-in, no Orbot needed
  - Hosted Channels
    - lightweight credit-based virtual channels that use an open and interoperable protocol
    - can open hosted channels to any node that supports the host side of the protocol
    - cannot be traced and do not require capital or chain fee costs
  - Split-payments support
    - the same invoice from A can be paid by multiple wallets (B, C, D and so on) atomically
    - perfect for splitting bills at restaurants
  - LNURL support
    - get channels with lnurl-channel, lnurl-hosted-channel
    - withdraw from services with lnurl-withdraw
    - login to websites with keyauth (lnurl-auth)
    - pay out to services with lnurl-pay and lightning address, possibly including
      - arbitrary comments
      - free names for tips
      - key and signed keyauth challenges that allow simultaneous payment and login or account referencing
      - unique public keys that allow later payer identification
      - reading `successAction`s that can be
        - free messages from the service to the wallet
        - URLs sent from the service
        - AES-encrypted secrets decryptable only with the payment preimage
  - NameDesc!
    - parse NameDesc invoices
    - optionally generate NameDesc invoices

![Obi Wan fights using the Open Bitcoin Wallet (OBW)](https://user-images.githubusercontent.com/1653275/186679611-c5c25d94-752a-4368-a0e4-7e7109fa5548.gif)


## How to build OBW from source

To build the _Open Bitcoin Wallet_ run this:

```
git clone https://github.com/nbd-wtf/obw.git
cd obw
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug`.

Other commands: `./gradlew installDebug`, `./gradlew assembleRelease`, `./gradlew bundleRelease` (with Gradle options `-PSTORE_FILE=... -PSTORE_PASSWORD=... -PKEY_PASSWORD=... -PKEY_ALIAS=...` when signing to publish to Google Play Store).

## Immortan

_Open Bitcoin Wallet_ (OBW) is based on [Immortan](https://github.com/nbd-wtf/immortan), a versatile, flexible and reasonable library for building lightweight Bitcoin and Lightning wallets.

## License

Apache.
