package circles.server

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli(System.getenv(), System.out, System.err).run(args.toList()))
}
