package dev.mooner.slplugin.sample

import dev.mooner.starlight.plugincore.api.Api
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.api.InstanceType
import dev.mooner.starlight.plugincore.project.Project

/**
 * Greets people; a global in every project while the sample plugin is loaded.
 *
 * StarLight AI reads this KDoc from the plugin's package, see guide:greetings.
 *
 * @see guide:greetings
 */
class Greeter {

    /**
     * A greeting for [name], such as `Hello, Kim!`.
     *
     * @param name Who to greet.
     * @param excited Ends the greeting with `!!` instead of `!`.
     */
    @JvmOverloads
    fun greet(name: String, excited: Boolean = false): String =
        "Hello, $name${if (excited) "!!" else "!"}"
}

class GreeterApi : Api<Greeter>() {

    override val name: String = "Greeter"

    override val objects: List<ApiObject> = getApiObjects<Greeter>()

    override val instanceClass: Class<Greeter> = Greeter::class.java

    override val instanceType: InstanceType = InstanceType.OBJECT

    override fun getInstance(project: Project): Any = Greeter()
}
