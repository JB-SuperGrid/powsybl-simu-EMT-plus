# Copyright (c) 2026, SuperGrid Institute
# All rights reserved. Proprietary and confidential.
#
# Keeps one PSCAD Professional open, through PSCAD's automation library (mhi.pscad), and builds - and runs - the project files
# it is given one by one on its standard input, a line each:
#
#     build<TAB><project.pscx>      or      run<TAB><project.pscx>
#
# What it did is in <name>.result beside the project, one key=value per line: status (ran, built, build_failed or failed),
# build_folder, an error line per error PSCAD or its compiler reported; PSCAD's messages are in <name>.log. Each project is
# unloaded once done. DONE is printed on its own line once a project is reported, and how long its load, build and run took
# on the standard error. PSCAD is quit at the end of the input, or at "quit".
#
#     python pscad_session.py [--fortran "<compiler>"] [--version 5.1.0] [--visible]
import argparse
import os
import re
import sys
import time
import traceback

import mhi.pscad

# The compiler's and the linker's own errors, which PSCAD passes on as plain output: the cause, where PSCAD's error only
# says no executable was made.
TOOL_ERROR = re.compile(r"fatal error|error [A-Z]+\d+|error #\d+|: error")

# What a reply starts with: anything else on the standard output (PSCAD's library's own prints) is not one.
DONE = "SIMUEMT-PSCAD-DONE"


def report(path, status, build_folder, errors):
    with open(path, "w", encoding="utf-8") as result:
        result.write(f"status={status}\n")
        result.write(f"build_folder={build_folder}\n")
        for error in errors:
            result.write("error=" + " ".join(str(error).split()) + "\n")


def handle(pscad, launch_error, command, path):
    path = os.path.abspath(path)
    name = os.path.splitext(os.path.basename(path))[0]
    directory = os.path.dirname(path)
    result = os.path.join(directory, name + ".result")
    status, build_folder, errors, lines, timings = "failed", "", [], [], []
    start = loaded = built = time.time()
    report(result, status, build_folder, ["PSCAD did not answer"])
    try:
        if launch_error:
            raise RuntimeError(launch_error)
        start = time.time()
        pscad.load(path)
        loaded = time.time()
        project = pscad.project(name)
        try:
            project.build()
            built = time.time()
            build_folder = project.temp_folder
            messages = project.messages()
            lines = [f"{m.status}: {m.text}" for m in messages]
            errors = [m.text for m in messages if str(m.status).lower() == "error"]
            errors += [m.text for m in messages if str(m.status).lower() != "error" and TOOL_ERROR.search(m.text)]
            if errors:
                status = "build_failed"
            elif command == "build":
                status = "built"
            else:
                project.run()
                timings.append(f"run {time.time() - built:.1f} s")
                status = "ran"
        finally:
            project.unload()
            timings = [f"load {loaded - start:.1f} s", f"build {built - loaded:.1f} s"] + timings
            print(f"{path}: " + ", ".join(timings) + f", total {time.time() - start:.1f} s", file=sys.stderr, flush=True)
    except Exception as exception:  # reported, not raised: the caller reads the result file
        errors.append(repr(exception))
        lines.append(traceback.format_exc())
    finally:
        with open(os.path.join(directory, name + ".log"), "w", encoding="utf-8") as log:
            log.write("\n".join(lines) + "\n")
        report(result, status, build_folder, errors)


def main():
    parser = argparse.ArgumentParser(description="Builds and runs PSCAD project files in one PSCAD")
    parser.add_argument("--fortran", help="the Fortran compiler, as PSCAD's settings name it")
    parser.add_argument("--version", default="5.1.0", help="the PSCAD version to launch")
    parser.add_argument("--visible", action="store_true", help="leave PSCAD's window up")
    args = parser.parse_args()

    pscad, launch_error = None, None
    try:
        pscad = mhi.pscad.launch(minimize=not args.visible, splash=False, version=args.version)
        if not pscad.licensed():
            launch_error = "PSCAD has no licence: the automation library needs Professional or Educational"
        elif args.fortran:
            pscad.settings(fortran_version=args.fortran)
    except Exception as exception:
        launch_error = "PSCAD did not start: " + repr(exception)
    try:
        for line in sys.stdin:
            line = line.rstrip("\r\n")
            if line == "quit":
                break
            command, _, path = line.partition("\t")
            if command not in ("build", "run") or not path:
                continue
            handle(pscad, launch_error, command, path)
            print(DONE, flush=True)
    finally:
        if pscad is not None:
            pscad.quit()
    return 0


if __name__ == "__main__":
    sys.exit(main())
