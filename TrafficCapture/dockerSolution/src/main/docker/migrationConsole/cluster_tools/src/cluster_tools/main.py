import os
import importlib
import argparse
import argcomplete
from console_link.environment import Environment


def list_tools():
    """Dynamically list all available tools by finding Python files in the tools directory."""
    tools_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../tools"))
    tools = [
        filename[:-3]
        for filename in os.listdir(tools_dir)
        if filename.endswith(".py") and filename != "__init__.py"
    ]
    return tools


def main(args=None):
    # Create the main parser
    parser = argparse.ArgumentParser(
        description="CLI tool for managing and running different utilities."
    )
    subparsers = parser.add_subparsers(dest="tool", help="The tool to run.")

    # Dynamically add subparsers for each tool
    for tool_name in list_tools():
        tool_module = importlib.import_module(f"tools.{tool_name}")
        tool_parser = subparsers.add_parser(tool_name, help=f"{tool_name} utility")

        # Check if the tool module has a 'define_arguments' function to define its arguments
        if hasattr(tool_module, "define_arguments"):
            tool_module.define_arguments(tool_parser)
            # Start Generation Here
            tool_parser.add_argument(
                '--config_file',
                type=str,
                default='/etc/migration_services.yaml',
                help='Path to the configuration file.'
            )
        else:
            # Raise an exception if the 'define_arguments' function is missing
            raise Exception(
                f"The tool '{tool_name}' does not have a 'define_arguments' function.
                  Please add one to specify its arguments.")

        tool_parser.set_defaults(func=tool_module.main)  # Set the main function as the handler

    argcomplete.autocomplete(parser)  # Enable argcomplete for bash completion

    args = args if args is not None else parser.parse_args()

    # If no specific tool is requested, list all available tools
    if not args.tool:
        print("Available tools:")
        for tool in list_tools():
            print(f"  - {tool}")
        print("\nRun `cluster_tools <tool>` to use a tool.")
    else:
        env = Environment(args.config_file)
        args.func(env, args)


if __name__ == "__main__":
    main()