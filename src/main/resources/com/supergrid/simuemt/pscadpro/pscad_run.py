# Copyright (c) 2026, SuperGrid Institute
# All rights reserved. Proprietary and confidential.
#
# Loads a PSCAD project file in PSCAD Professional, builds it and runs it, through PSCAD's automation library (mhi.pscad).
# What it did is in <name>.result beside the project, one key=value per line: status (ran, built, build_failed or
# failed), build_folder, an error line per error PSCAD or its compiler reported.
#
#     python pscad_run.py <project.pscx> [--fortran "<compiler>"] [--version 5.1.0] [--no-run] [--visible]
import argparse
import os
import re
import sys

import mhi.pscad

# The compiler's and the linker's own errors, which PSCAD passes on as plain output: the cause, where PSCAD's error only
# says no executable was made.
TOOL_ERROR = re.compile(r"fatal error|error [A-Z]+\d+|error #\d+|: error")


def report(path, status, build_folder, errors):
    with open(path, "w", encoding="utf-8") as result:
        result.write(f"status={status}\n")
        result.write(f"build_folder={build_folder}\n")
        for error in errors:
            result.write("error=" + " ".join(str(error).split()) + "\n")


def main():
    parser = argparse.ArgumentParser(description="Builds and runs a PSCAD project file")
    parser.add_argument("pscx", help="the project file")
    parser.add_argument("--no-run", action="store_true", help="build the project, do not run it")
    parser.add_argument("--fortran", help="the Fortran compiler, as PSCAD's settings name it")
    parser.add_argument("--version", default="5.1.0", help="the PSCAD version to launch")
    parser.add_argument("--visible", action="store_true", help="leave PSCAD's window up")
    args = parser.parse_args()
    path = os.path.abspath(args.pscx)
    name = os.path.splitext(os.path.basename(path))[0]
    result = os.path.join(os.path.dirname(path), name + ".result")

    status, build_folder, errors = "failed", "", []
    report(result, status, build_folder, ["PSCAD did not start"])
    pscad = mhi.pscad.launch(minimize=not args.visible, splash=False, version=args.version)
    try:
        if not pscad.licensed():
            raise RuntimeError("PSCAD has no licence: the automation library needs Professional or Educational")
        if args.fortran:
            pscad.settings(fortran_version=args.fortran)
        pscad.load(path)
        project = pscad.project(name)
        project.build()
        build_folder = project.temp_folder
        messages = project.messages()
        errors = [m.text for m in messages if str(m.status).lower() == "error"]
        errors += [m.text for m in messages if str(m.status).lower() != "error" and TOOL_ERROR.search(m.text)]
        if errors:
            status = "build_failed"
        elif args.no_run:
            status = "built"
        else:
            project.run()
            status = "ran"
    except Exception as exception:  # reported, not raised: the caller reads the result file
        errors.append(repr(exception))
    finally:
        report(result, status, build_folder, errors)
        pscad.quit()
    return 0 if status in ("built", "ran") else 1


if __name__ == "__main__":
    sys.exit(main())
