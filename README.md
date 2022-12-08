<a href="https://nbd.wtf"><img align="right" height="196" src="https://user-images.githubusercontent.com/1653275/194609043-0add674b-dd40-41ed-986c-ab4a2e053092.png" /></a>

# The _Open Bitcoin Wallet_

The _Open Bitcoin Wallet_ is an Android Bitcoin and Lightning wallet, a fork of [_Simple Bitcoin Wallet_](https://github.com/btcontract/wallet) focused on providing a pleasant and simple experience with advanced features.

Some of the features it includes are:

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
    - lnurl-channel, lnurl-hosted-channel
    - lnurl-pay, lightning address, comments, message, URL and AES-encrypted `successAction`s
    - lnurl-withdraw
    - keyauth (lnurl-auth)
  - NameDesc support

![obiwan](https://user-images.githubusercontent.com/1653275/186679611-c5c25d94-752a-4368-a0e4-7e7109fa5548.gif)

## IMMORTAN

_Open Bitcoin Wallet_ is based on [IMMORTAN](https://github.com/nbd-wtf/immortan), a versatile, flexible and reasonable library for building lightweight Bitcoin and Lightning wallets.

## How to install

You have 3 options right now:

1. Grab an APK from the [releases](https://github.com/nbd-wtf/obw/releases) page
2. Download from [Google Play](https://play.google.com/store/apps/details?id=wtf.nbd.obw)
3. Build from source (see instructions below)

## How to build from source

Run this:

```
git clone https://github.com/nbd-wtf/obw.git
cd obw
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug`.

Other commands: `./gradlew installDebug`, `./gradlew assembleRelease`, `./gradlew bundleRelease` (with Gradle options `-PSTORE_FILE=... -PSTORE_PASSWORD=... -PKEY_PASSWORD=... -PKEY_ALIAS=...` when signing to publish to Google Play Store).

## License

Apache.
