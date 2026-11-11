#!/usr/bin/env python3
"""Proves the checker goes red on planted bad files and green on a good one: python3 scripts/test_check_translations.py"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check-translations.py")

SOURCE = r"""<resources>
    <string name="hello">Hello %1$s, you have %2$d messages</string>
    <string name="tap">Tap <b>Allow</b> to continue</string>
    <string name="quote">Don\'t stop</string>
    <plurals name="items"><item quantity="one">%d item</item><item quantity="other">%d items</item></plurals>
    <string-array name="days"><item>Mon</item><item>Tue</item></string-array>
    <string name="skip" translatable="false">Skip me</string>
</resources>"""

GOOD_RU = """<resources>
    <string name="hello">Привет, %1$s, у вас %2$d сообщений</string>
    <string name="tap">Нажмите <b>Разрешить</b>, чтобы продолжить</string>
    <string name="quote">Не останавливайтесь</string>
    <plurals name="items"><item quantity="one">%d элемент</item><item quantity="few">%d элемента</item><item quantity="many">%d элементов</item><item quantity="other">%d элемента</item></plurals>
    <string-array name="days"><item>Пн</item><item>Вт</item></string-array>
</resources>"""


def run(files):
    with tempfile.TemporaryDirectory() as res:
        os.makedirs(os.path.join(res, "values"))
        with open(os.path.join(res, "values", "strings.xml"), "w", encoding="utf-8") as f:
            f.write(SOURCE)
        for folder, text in files.items():
            os.makedirs(os.path.join(res, folder))
            with open(os.path.join(res, folder, "strings.xml"), "w", encoding="utf-8") as f:
                f.write(text)
        r = subprocess.run([sys.executable, "-I", CHECK, "--res", res], capture_output=True, text=True)
        return r.returncode, r.stdout


class Checker(unittest.TestCase):
    def red(self, folder, text, needle):
        code, out = run({folder: text})
        self.assertEqual(code, 1, out)
        self.assertIn(needle, out)

    def test_green(self):
        code, out = run({"values-ru": GOOD_RU})
        self.assertEqual(code, 0, out)

    def test_missing_key(self):
        self.red("values-ru", GOOD_RU.replace('    <string name="tap">Нажмите <b>Разрешить</b>, чтобы продолжить</string>\n', ""), "missing string 'tap'")

    def test_placeholder_dropped(self):
        self.red("values-ru", GOOD_RU.replace("%2$d сообщений", "сообщений"), "placeholders")

    def test_placeholder_type_changed(self):
        self.red("values-ru", GOOD_RU.replace("%2$d", "%2$s"), "placeholders")

    def test_html_lost(self):
        self.red("values-ru", GOOD_RU.replace("<b>Разрешить</b>", "Разрешить"), "HTML tags differ")

    def test_apostrophe(self):
        self.red("values-fr", GOOD_RU.replace("Не останавливайтесь", "N'arrêtez pas").replace("Привет", "Bonjour").replace("Нажмите", "Touchez").replace("Разрешить", "Autoriser").replace("чтобы продолжить", "pour continuer"), "apostrophe")

    def test_plural_categories(self):
        self.red("values-ru", GOOD_RU.replace('<item quantity="few">%d элемента</item>', ""), "plural categories missing")

    def test_japanese_must_be_other_only(self):
        ja = GOOD_RU.replace("Привет", "こんにちは").replace("у вас", "あなたの").replace("сообщений", "件")
        self.red("values-ja", ja, "plural categories")

    def test_english_left(self):
        self.red("values-ru", GOOD_RU.replace("Привет, %1$s, у вас %2$d сообщений", "Hello %1$s, you have %2$d messages"), "identical to English")

    def test_wrong_script(self):
        # Hindi text in a Tamil folder, and Cyrillic in a Latin language
        ta = GOOD_RU.replace("Привет", "नमस्ते").replace("у вас", "உங்களுக்கு").replace("сообщений", "செய்திகள்")
        self.red("values-ta", ta, "wrong script")
        self.red("values-de", GOOD_RU.replace("Привет", "Hallo").replace("у вас", "du hast"), "wrong script")

    def test_english_run_in_non_latin(self):
        self.red("values-ru", GOOD_RU.replace("Не останавливайтесь", "Please do not stop right now"), "English")

    def test_bad_xml(self):
        self.red("values-ru", GOOD_RU.replace("</resources>", ""), "invalid XML")

    def test_array_length(self):
        self.red("values-ru", GOOD_RU.replace("<item>Вт</item>", ""), "items")


if __name__ == "__main__":
    unittest.main()
