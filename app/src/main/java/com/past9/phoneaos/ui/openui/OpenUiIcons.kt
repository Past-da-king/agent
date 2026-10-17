package com.past9.phoneaos.ui.openui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons for generated cards. The model names Lucide icons ("calendar", "map-pin") and writes plain labels ("Bedrooms",
 * "Deposit"); both are mapped to Material icons by word, so a card gets its icons whether or not the model asked for any.
 */
object OpenUiIcons {
    private val words: List<Pair<List<String>, ImageVector>> by lazy { listOf(
        listOf("rent", "price", "cost", "amount", "fee", "total", "pay", "paid", "salary", "income", "money", "dollar", "banknote", "wallet", "bill", "invoice", "receipt", "fare") to Icons.Rounded.Payments,
        listOf("flight", "airline", "plane", "gate", "boarding", "airport", "takeoff") to Icons.Rounded.Flight,
        listOf("ref", "reference", "confirmation", "ticket", "pnr", "code", "pass") to Icons.Rounded.ConfirmationNumber,
        listOf("seat") to Icons.Rounded.EventSeat,
        listOf("balance", "bank", "account", "transfer", "statement") to Icons.Rounded.AccountBalance,
        listOf("deposit", "saving", "budget") to Icons.Rounded.Savings,
        listOf("card", "credit", "debit") to Icons.Rounded.CreditCard,
        listOf("bedroom", "bed", "sleep", "room") to Icons.Rounded.Bed,
        listOf("size", "area", "m2", "sqm", "floor", "ruler") to Icons.Rounded.SquareFoot,
        listOf("home", "house", "flat", "apartment", "property", "listing", "building") to Icons.Rounded.Home,
        listOf("available", "date", "when", "day", "event", "calendar", "meeting", "appointment", "booking", "viewing", "due", "deadline", "schedule") to Icons.Rounded.Event,
        listOf("time", "depart", "arrive", "duration", "hour", "clock", "late", "wait", "pending", "timer") to Icons.Rounded.Schedule,
        listOf("luggage", "bag", "baggage", "suitcase", "trip", "travel") to Icons.Rounded.Luggage,
        listOf("hotel", "stay", "night", "lodging") to Icons.Rounded.Hotel,
        listOf("car", "drive", "ride", "taxi", "uber", "bolt ride", "parking", "fuel", "petrol") to Icons.Rounded.DirectionsCar,
        listOf("train", "bus", "transit", "commute") to Icons.Rounded.DirectionsTransit,
        listOf("location", "address", "where", "map", "pin", "place", "suburb", "city", "street", "near", "distance", "gps") to Icons.Rounded.Place,
        listOf("wifi", "internet", "network", "fibre", "fiber") to Icons.Rounded.Wifi,
        listOf("phone", "call", "mobile", "tel", "number") to Icons.Rounded.Call,
        listOf("mail", "email", "inbox", "message", "reply", "to", "from", "subject", "cc", "recipient", "body") to Icons.Rounded.Mail,
        listOf("attachment", "attached", "attach") to Icons.Rounded.AttachFile,
        listOf("send", "share", "forward") to Icons.AutoMirrored.Rounded.Send,
        listOf("person", "user", "name", "contact", "agent", "landlord", "owner", "host", "guest", "who", "lerato", "people") to Icons.Rounded.Person,
        listOf("team", "group", "family", "friends", "attendee", "helper", "helpers") to Icons.Rounded.Groups,
        listOf("food", "restaurant", "dinner", "lunch", "eat", "menu", "meal", "grocer", "spar", "shop", "store", "cart", "buy", "order") to Icons.Rounded.ShoppingCart,
        listOf("coffee", "cafe", "café", "tea", "drink") to Icons.Rounded.LocalCafe,
        listOf("star", "rating", "review", "score", "favourite", "favorite") to Icons.Rounded.Star,
        listOf("lock", "secure", "security", "password", "private", "safe") to Icons.Rounded.Lock,
        listOf("alert", "warning", "risk", "caution", "overdue", "expir") to Icons.Rounded.Warning,
        listOf("error", "fail", "declin", "reject", "cancel", "danger", "problem") to Icons.Rounded.ErrorOutline,
        listOf("check", "done", "success", "approve", "confirm", "ok", "complete", "yes") to Icons.Rounded.CheckCircle,
        listOf("info", "note", "about", "detail", "status") to Icons.Rounded.Info,
        listOf("doc", "document", "file", "pdf", "report", "contract", "lease", "text", "page") to Icons.Rounded.Description,
        listOf("search", "find", "look") to Icons.Rounded.Search,
        listOf("link", "url", "website", "web", "open") to Icons.Rounded.Link,
        listOf("work", "job", "career", "briefcase", "office", "company") to Icons.Rounded.Work,
        listOf("trend", "growth", "increase", "up", "profit") to Icons.AutoMirrored.Rounded.TrendingUp,
        listOf("decrease", "down", "loss", "drop") to Icons.AutoMirrored.Rounded.TrendingDown,
        listOf("task", "todo", "to-do", "list", "step", "checklist") to Icons.Rounded.Checklist,
        listOf("sun", "weather", "forecast", "temperature", "clear") to Icons.Rounded.WbSunny,
        listOf("cloud", "rain", "storm") to Icons.Rounded.Cloud,
        listOf("bolt", "fast", "power", "electric", "energy", "load") to Icons.Rounded.Bolt,
        listOf("tag", "label", "category", "sale", "deal", "discount") to Icons.Rounded.Sell,
        listOf("photo", "image", "picture", "camera") to Icons.Rounded.Image,
        listOf("music", "song", "play", "audio", "track") to Icons.Rounded.MusicNote,
        listOf("health", "doctor", "heart", "medical") to Icons.Rounded.Favorite,
        listOf("book", "read", "study", "learn", "school", "course") to Icons.Rounded.MenuBook,
    ) }

    /** The icon for a Lucide-style name or a free label; null when nothing fits. */
    fun forText(text: String?): ImageVector? {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val parts = t.split(Regex("[^a-z0-9é+]+")).filter { it.isNotEmpty() }
        // Whole-word hits win over fragments ("bed" in "bedrooms" is still right, "up" in "upload" is not).
        words.firstOrNull { (keys, _) -> keys.any { k -> parts.any { it == k || (k.length >= 4 && it.startsWith(k)) } } }?.let { return it.second }
        return words.firstOrNull { (keys, _) -> keys.any { k -> k.length >= 5 && t.contains(k) } }?.second
    }

    /** The icon for a card or tile: what the words say, else a neutral one. */
    fun forTextOr(text: String?, fallback: ImageVector = Icons.Rounded.Insights): ImageVector = forText(text) ?: fallback

    /** A button that only opens or shows something ("Details", "Open listing"), as opposed to one that decides or sends. */
    fun navigates(label: String?): Boolean = forAction(label) == Icons.Rounded.OpenInNew

    /** The leading icon for an action button, from its label. */
    fun forAction(label: String?): ImageVector? {
        val t = label?.lowercase().orEmpty()
        return when {
            Regex("approve|confirm|accept|yes|done|ok\\b|allow").containsMatchIn(t) -> Icons.Rounded.Check
            Regex("decline|reject|cancel|no\\b|deny|dismiss|skip").containsMatchIn(t) -> Icons.Rounded.Close
            Regex("book|schedule|viewing|reserve|appointment").containsMatchIn(t) -> Icons.Rounded.Event
            Regex("open|view|see|visit|read|details|listing|link").containsMatchIn(t) -> Icons.Rounded.OpenInNew
            Regex("call|phone|ring").containsMatchIn(t) -> Icons.Rounded.Call
            Regex("boarding|ticket|pass\\b").containsMatchIn(t) -> Icons.Rounded.ConfirmationNumber
            Regex("cart|basket|bag").containsMatchIn(t) -> Icons.Rounded.AddShoppingCart
            Regex("pay|buy|order|checkout|tip").containsMatchIn(t) -> Icons.Rounded.Payments
            Regex("send|reply|message|email|forward|share").containsMatchIn(t) -> Icons.AutoMirrored.Rounded.Send
            Regex("remind|snooze|later").containsMatchIn(t) -> Icons.Rounded.Alarm
            Regex("search|find|compare|check").containsMatchIn(t) -> Icons.Rounded.Search
            else -> null
        }
    }
}
