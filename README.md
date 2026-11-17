# Sd IDE

## Android files and storage

The project workspace is stored in the app-specific external files directory:

`Android/data/<applicationId>/files/SdIDE/active_project`

The current application ID is `com.aistudio.sdide.kvnqmp`. This keeps the app
install-compatible with the existing APK. Android grants the app access to this
directory without broad storage permissions; Android's file manager may restrict
direct browsing of `Android/data`, so use the IDE's file manager or Android's
document picker when needed. Existing projects in the app's internal
`sd_workspace/active_project` are copied there on first launch.

From the top-right menu, the IDE can import source files (`.sd`, `.sdpp`,
`.sdall`, or `.txt`) and export the open source file using Android's document
picker. The **Quản lý bộ nhớ và tệp dữ liệu** screen manages files used by the
language file APIs: create, import, export, and delete. Source tabs continue to
support creating and deleting source files.

## SdIDE bytecode export

The bytecode export validates and tokenizes the selected entry file, then writes
a versioned binary token stream. `.sdbin` starts with `SDB1`; `.sdppbin` starts
with `SPP1`. Each file contains a format version, token count, and token type,
source position, newline flag, and UTF-8 lexeme for each token. This is a
portable SdIDE bytecode container, not native CPU machine code or an executable
binary. Native `.bin` output requires a separate compiler and a specified target
architecture.

## Offline Sd / Sd++ libraries

Open the top-right menu and choose **Thư viện Sd / Sd++**. The built-in
`ioprt`, `math`, `str`, `mem`, `file`, `sys`, and `draw` APIs are already part
of the interpreter. Install or remove additional source modules from `.sdlib`
files; installed modules are available to both live diagnostics and program
execution with `imp*module.ed` (Sd) or `imp*module.$` (Sd++).

A `.sdlib` is a ZIP archive containing `manifest.properties` and source files
under `src/`. Example manifest:

```properties
format=1
name=collections
version=1.0.0
description=Collection helpers for Sd
entry=collections.sd
```

The manifest `entry` names a source file in `src/`; the file basename is the
module name used by `imp*`. Optional helper modules can be included alongside
the entry file. Packages are installed locally without network access, limited
to 8 MiB, 32 source files, and 1 MiB per source file. Duplicate module names
across installed packages are rejected. Only `.sd` and `.sdpp` source files are
accepted. This milestone does not add C/C++ or Rust native-library loading or an
online registry.

The sample package [`examples/mathplus.sdlib`](./examples/mathplus.sdlib)
installs a `mathplus` module with `square(int)`. After installing it, use
`imp*mathplus.ed` and call `mathplus.square(7)`.

## Windows desktop version

The Windows desktop app shares the Sd interpreter and source-file formats with
the Android app. It supports opening a project folder, editing `.sd`, `.sdpp`,
`.sdall`, and `.txt` files, and running programs. It is a separate desktop
interface; Android-specific screens and controls are not included.

To create a Windows installer, run the **Build Sd IDE Windows installer**
workflow on GitHub Actions and download the `Sd-IDE-Windows-Installer` artifact.
The installer is built on Windows and includes its Java runtime.

To move a project between Android and Windows, copy its source files and
`.sdide_entry` marker into a folder, then open that folder in the Windows app.
The Android project is stored under
`Android/data/<applicationId>/files/SdIDE/active_project`; connect the device
to a computer and copy the project files from there. Both apps use the same
Sd source formats, but this is file-based sharing, not live network
collaboration.
