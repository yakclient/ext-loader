package net.yakclient.components.extloader.api.extension.archive

import net.yakclient.archives.ArchiveHandle
import net.yakclient.components.extloader.api.extension.archive.ExtensionArchiveReference

public interface ExtensionArchiveHandle : ArchiveHandle {
    public val reference: ExtensionArchiveReference
}