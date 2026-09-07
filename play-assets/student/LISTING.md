# Play listing pack — BiteSite (customer app)

Package `in.bitesite.app`. Everything here is a starting point you can edit in Play
Console; nothing is auto-submitted.

## Assets in this folder

| File | Play field | Status |
|---|---|---|
| `play-icon-512.png` | App icon, 512×512 | Ready (32-bit PNG, no alpha) |
| `play-feature-graphic-1024x500.png` | Feature graphic, 1024×500 | Ready |
| Phone screenshots | 2–8 required | **Missing — needs the device** |

Screenshots are the only asset left, and they have to come off a real handset. Minimum
two, and the listing reads much better with four to six. Worth capturing: the outlet
picker, a menu, the cart, the live order screen with the countdown, and the order-ready
state.

## Short description (80 char limit)

```
Order ahead from your campus canteen and skip the queue.
```

56 characters.

## Full description

```
BiteSite is canteen pre-ordering for your campus.

Pick your canteen, browse today's menu, pay once, and collect your order the moment
it is ready. No queue, no waiting at the counter, no wondering whether the kitchen
has started on it.

HOW IT WORKS

1. Sign in with your college account.
2. Choose your canteen. If your campus has several, the app can pick the nearest one.
3. Add what you want and pay.
4. Watch the order move from paid, to preparing, to ready for pickup.
5. Collect it.

WHAT YOU GET

- Live order status, so you know exactly when to walk over
- A notification the moment your order is ready
- A short window to cancel a misclick, with the refund handled automatically
- Your cart kept if you close the app and come back
- Card, UPI and netbanking through Razorpay

BiteSite is available at colleges that have signed up for it. You will need an account
at a participating campus to place an order.
```

## App access — required, do not skip

The entire app sits behind a login, so Play cannot review it without credentials. In
**App access**, choose "All or some functionality is restricted" and supply a working
student account for a campus with an open outlet and a stocked menu. Reviews get
rejected for this more often than for anything else in the listing.

## Data safety answers

Grounded in what the code actually does, not what seems likely.

| Question | Answer | Why |
|---|---|---|
| Personal info: name, email | **Collected**, linked to the user | Account record |
| Phone number | **Collected** if supplied | Optional field, used for OTP |
| Payment info | **Not collected** | Razorpay handles it. Only the order amount, their reference IDs and the payment status are stored, never card, UPI or bank details |
| **Location** | **Not collected** | `select-outlet.html` reads `navigator.geolocation` and does the nearest-outlet maths in the browser. The coordinates are never transmitted, stored or shared. The permission is declared, so expect to explain this |
| Device or other IDs | **Collected** | Push tokens in `fcm_tokens`, so order alerts can be delivered |
| App activity: order history | **Collected**, linked to the user | Orders, status, and the outlet they were placed at |
| Support messages | **Collected** | Grievance subject and body |
| Data encrypted in transit | **Yes** | HTTPS throughout |
| Users can request deletion | **Yes** | Self-service in My account; identifying fields are stripped and order records anonymised |
| Data shared with third parties | **Razorpay only**, as payment processor | Under their own policy |

Privacy policy URL: `https://app.bitesite.in/privacy-policy`

## Content rating and audience

- Target audience is adults and older teens. Do **not** mark it child-directed: it takes
  payments, which pulls it into the Families policy if you do.
- No ads, no in-app purchases in Play's sense. Payments are for physical goods (food),
  so Razorpay is allowed and Play Billing is not required. Be ready to say that if asked.

## Permissions that will show on the listing

`NFC` and `READ_BASIC_PHONE_STATE` come from the Razorpay SDK, not from anything
BiteSite does. Neither is in Play's restricted set that needs a declaration form, but
they will appear, so know where they came from.
