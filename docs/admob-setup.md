## AdMob Setup

Storyline ab default se Google ke official test ad IDs use karti hai. Isliye current builds me test ads safe rahenge jab tak real AdMob IDs explicitly set na kiye jayen.

### Current default

- `ADMOB_APP_ID`
- `ADMOB_BANNER_UNIT_ID`
- `ADMOB_REWARDED_HD_UNLOCK_UNIT_ID`

Ye vars empty rahenge to app automatically test IDs use karegi.

### Production par switch kaise karein

GitHub repository variables me add karo:

- `ADMOB_APP_ID`
- `ADMOB_BANNER_UNIT_ID`
- `ADMOB_REWARDED_HD_UNLOCK_UNIT_ID`

Uske baad next `Android CI Build` me APK prod IDs ke saath build ho jayegi.

### Recommended placement

- Home screen: adaptive banner
- Export flow: post-export interstitial
- Watermark removal / premium unlock: rewarded ad

### Important

- Real ads live karne se pehle AdMob account me app aur ad units create karo.
- Review period ke dauran low traffic aur test devices use karo.
- Editor preview ke beech aggressive interstitial mat lagao.
