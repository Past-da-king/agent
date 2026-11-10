# Third-party notices

The background screen (`app/src/main/java/com/past9/phoneaos/screen/`) follows the approach of two open-source
projects. No file of theirs is copied whole; the parts that follow their code are named below, with their licences.

## scrcpy

https://github.com/Genymobile/scrcpy (read at v5.0.1, commit a60891aea193d92e7e5c3942700eca63f9d19a5f)

Used as the reference for: the virtual display flags of `--new-display` and the Android versions each needs,
presenting as the `com.android.shell` package when creating the display, sending input events that carry a display
id, and the per-display keyboard policy. In this repo: `screen/helper/HelperSystem.kt`.

    Copyright (C) 2018 Genymobile
    Copyright (C) 2018-2026 Romain Vimont

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Agent-VScreen

https://github.com/kilolonion/agent-vscreen (read at v1.1.7, commit 7e2e01491797848a82bb8bfe611842fb87fb9d10)

Used as the reference for: the order in which display flag sets are tried (richest first, plainer for stricter
systems), keeping a surface on the display for its whole life and emptying its picture queue so the system does not
put the display to sleep, and creating the display from a process that runs as the shell user so that other apps
may be placed on it. In this repo: `screen/helper/HelperSystem.kt`, `screen/helper/HelperMain.kt`.

    MIT License

    Copyright (c) 2026 kilolonion

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## Shizuku API

https://github.com/RikkaApps/Shizuku-API (`dev.rikka.shizuku:api` and `:provider` 13.1.5, MIT licence, Copyright (c) 2021 RikkaW)

A library dependency, used unchanged, to ask Shizuku (when the person has it) to start the screen helper.
